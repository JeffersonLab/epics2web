package org.jlab.epics2web.epics;

import java.io.Closeable;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Finds frozen PVs: PVs whose monitor has stopped working while the IOC still serves them, which
 * restarting epics2web would be expected to fix. A PV whose IOC is down isn't frozen.
 *
 * <p>Suspicious PVs get a short-lived subscription in a second, independent CA context, with its
 * own virtual circuits. The subscription is made like the monitor's, so the IOC applies the same
 * deadband to both, and comparing them doesn't mistake a change within the deadband for a missed
 * update. A PV is frozen when either:
 *
 * <ul>
 *   <li>its monitor has been disconnected, or still connecting, for longer than the grace period,
 *       but the independent subscription connects and receives the PV; or
 *   <li>its monitor, which used to get updates, has gone quiet, and the independent subscription
 *       receives a change the monitor doesn't.
 * </ul>
 *
 * <p>A PV stops being frozen when its monitor receives an update. Through a gateway, both contexts
 * reach the PV through the gateway, so this finds problems between epics2web and the gateway, not
 * inside it.
 */
public class FrozenPvDetector implements Closeable {

  private static final Logger LOGGER = Logger.getLogger(FrozenPvDetector.class.getName());

  /** What the detector needs to know about a monitor. */
  public record MonitorView(
      ChannelMonitor.MonitorState state,
      Instant stateChanged,
      Instant lastUpdate,
      long updateCount) {}

  /** A subscription to a PV in the independent CA context. */
  public interface Probe extends Closeable {

    /** Called before each evaluation, from the detector's thread. */
    default void poll() {}

    /**
     * @return When the first update arrived, which shows the IOC serves the PV, or null
     */
    Instant connectedAt();

    /**
     * @return When the first update after the initial one arrived, which shows the PV changed, or
     *     null
     */
    Instant firstChangeAt();

    @Override
    void close();
  }

  /** Starts probes. */
  public interface ProbeFactory {
    Probe start(String pv) throws Exception;
  }

  /** A frozen PV: since when, and why. */
  public record FrozenPv(Instant since, String reason) {}

  private enum Kind {
    NOT_CONNECTED,
    QUIET
  }

  private record ProbeSession(Probe probe, Kind kind, Instant started, long updateCountAtStart) {}

  private final Supplier<Map<String, MonitorView>> monitors;
  private final ProbeFactory probes;
  private final Clock clock;
  private final Duration grace;
  private final Duration quiet;
  private final Duration window;
  private final Duration margin;
  private final Duration reprobe;
  private final int maxProbes;

  private final Map<String, ProbeSession> active = new HashMap<>();
  private final Map<String, Instant> lastProbed = new HashMap<>();
  private final Map<String, Long> updateCountWhenFrozen = new HashMap<>();
  private final Map<String, FrozenPv> frozen = new ConcurrentHashMap<>();

  /**
   * Create a new FrozenPvDetector. Timings derive from the check interval: a PV is quiet after 6
   * intervals without an update, a probe lasts at most 6 intervals, a PV is probed at most once
   * every 30 intervals, and a probe's evidence must be half an interval old to count.
   *
   * @param monitors Supplies the monitors to check, by PV
   * @param probes Starts subscriptions in the independent context
   * @param clock The clock
   * @param grace How long a monitor may be disconnected before it's checked
   * @param interval How often check is called
   * @param maxProbes The most probes open at once
   */
  public FrozenPvDetector(
      Supplier<Map<String, MonitorView>> monitors,
      ProbeFactory probes,
      Clock clock,
      Duration grace,
      Duration interval,
      int maxProbes) {
    this.monitors = monitors;
    this.probes = probes;
    this.clock = clock;
    this.grace = grace;
    this.quiet = interval.multipliedBy(6);
    this.window = interval.multipliedBy(6);
    this.margin = interval.dividedBy(2);
    this.reprobe = interval.multipliedBy(30);
    this.maxProbes = maxProbes;
  }

  /**
   * Views of the given monitors, for the detector.
   *
   * @param monitorMap The monitors, by PV
   * @return The views, by PV
   */
  public static Map<String, MonitorView> viewsOf(Map<String, ChannelMonitor> monitorMap) {
    Map<String, MonitorView> views = new HashMap<>();
    for (Map.Entry<String, ChannelMonitor> entry : monitorMap.entrySet()) {
      ChannelMonitor monitor = entry.getValue();
      views.put(
          entry.getKey(),
          new MonitorView(
              monitor.getState(),
              monitor.getStateChanged(),
              monitor.getLastTimestamp() == null ? null : monitor.getLastTimestamp().toInstant(),
              monitor.getUpdateCount()));
    }
    return views;
  }

  /** Run one check: release recovered PVs, evaluate open probes, and start new ones. */
  public synchronized void check() {
    Instant now = clock.instant();
    Map<String, MonitorView> views = monitors.get();

    releaseRecovered(views, now);
    evaluateProbes(views, now);
    startProbes(views, now);

    lastProbed.keySet().retainAll(views.keySet());
  }

  /**
   * @return The frozen PVs, by PV
   */
  public Map<String, FrozenPv> getFrozen() {
    return Map.copyOf(frozen);
  }

  @Override
  public synchronized void close() {
    for (ProbeSession session : active.values()) {
      session.probe().close();
    }
    active.clear();
  }

  private void releaseRecovered(Map<String, MonitorView> views, Instant now) {
    for (Iterator<String> it = frozen.keySet().iterator(); it.hasNext(); ) {
      String pv = it.next();
      MonitorView view = views.get(pv);
      if (view == null) { // No longer monitored
        updateCountWhenFrozen.remove(pv);
        it.remove();
      } else if (view.updateCount() > updateCountWhenFrozen.get(pv)) {
        LOGGER.log(
            Level.INFO,
            "PV {0} recovered after being frozen for {1} s",
            new Object[] {pv, Duration.between(frozen.get(pv).since(), now).toSeconds()});
        updateCountWhenFrozen.remove(pv);
        it.remove();
      }
    }
  }

  private void evaluateProbes(Map<String, MonitorView> views, Instant now) {
    for (Iterator<Map.Entry<String, ProbeSession>> it = active.entrySet().iterator();
        it.hasNext(); ) {
      Map.Entry<String, ProbeSession> entry = it.next();
      String pv = entry.getKey();
      ProbeSession session = entry.getValue();
      Probe probe = session.probe();
      MonitorView view = views.get(pv);

      probe.poll();

      String reason = null;
      boolean done;
      if (view == null || view.updateCount() > session.updateCountAtStart()) {
        done = true; // No longer monitored, or the monitor is getting updates
      } else if (session.kind() == Kind.NOT_CONNECTED
          && view.state() != ChannelMonitor.MonitorState.CONNECTED
          && isOlderThanMargin(probe.connectedAt(), now)) {
        reason =
            "The IOC serves it, but its monitor has been "
                + view.state()
                + " since "
                + view.stateChanged();
        done = true;
      } else if (session.kind() == Kind.QUIET && isOlderThanMargin(probe.firstChangeAt(), now)) {
        reason =
            "The IOC sent a change its monitor didn't receive; last update " + view.lastUpdate();
        done = true;
      } else {
        done = !now.isBefore(session.started().plus(window)); // Inconclusive
      }

      if (reason != null) {
        LOGGER.log(Level.WARNING, "PV {0} is frozen: {1}", new Object[] {pv, reason});
        frozen.put(pv, new FrozenPv(now, reason));
        updateCountWhenFrozen.put(pv, view.updateCount());
      }
      if (done) {
        probe.close();
        it.remove();
      }
    }
  }

  private void startProbes(Map<String, MonitorView> views, Instant now) {
    List<Map.Entry<String, Kind>> candidates = new ArrayList<>();
    for (Map.Entry<String, MonitorView> entry : views.entrySet()) {
      String pv = entry.getKey();
      Instant probed = lastProbed.get(pv);
      if (frozen.containsKey(pv)
          || active.containsKey(pv)
          || (probed != null && now.isBefore(probed.plus(reprobe)))) {
        continue;
      }
      Kind kind = kindOf(entry.getValue(), now);
      if (kind != null) {
        candidates.add(Map.entry(pv, kind));
      }
    }

    // Least recently probed first, so every candidate gets its turn
    candidates.sort(Comparator.comparing(c -> lastProbed.getOrDefault(c.getKey(), Instant.MIN)));

    for (Map.Entry<String, Kind> candidate : candidates) {
      if (active.size() >= maxProbes) {
        break;
      }
      String pv = candidate.getKey();
      lastProbed.put(pv, now);
      try {
        Probe probe = probes.start(pv);
        active.put(
            pv, new ProbeSession(probe, candidate.getValue(), now, views.get(pv).updateCount()));
      } catch (Exception e) {
        LOGGER.log(Level.FINE, "Unable to probe " + pv, e);
      }
    }
  }

  private Kind kindOf(MonitorView view, Instant now) {
    if (view.state() != ChannelMonitor.MonitorState.CONNECTED) {
      return now.isBefore(view.stateChanged().plus(grace)) ? null : Kind.NOT_CONNECTED;
    }
    // Only PVs that have changed since subscribing can show updates going missing
    if (view.updateCount() >= 2
        && view.lastUpdate() != null
        && !now.isBefore(view.lastUpdate().plus(quiet))) {
      return Kind.QUIET;
    }
    return null;
  }

  private boolean isOlderThanMargin(Instant time, Instant now) {
    return time != null && !now.isBefore(time.plus(margin));
  }
}
