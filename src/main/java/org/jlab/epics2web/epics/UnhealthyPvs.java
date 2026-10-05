package org.jlab.epics2web.epics;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Finds the PVs the healthcheck reports: monitored PVs that haven't been connected for longer than
 * the grace period, and frozen PVs. The healthcheck and the disconnected PVs report both use it, so
 * they list the same PVs.
 */
public final class UnhealthyPvs {

  private UnhealthyPvs() {}

  /**
   * A reported PV.
   *
   * @param name The PV name
   * @param state The monitor's state, or null if the PV is frozen but no longer monitored
   * @param notConnected How long the PV hasn't been connected, counted from when it disconnected or
   *     from when monitoring began if it never connected; null if it's connected or still within
   *     the grace period, and reported only because it's frozen
   * @param frozen Why the PV is frozen, or null if it isn't
   */
  public record UnhealthyPv(
      String name,
      ChannelMonitor.MonitorState state,
      Duration notConnected,
      FrozenPvDetector.FrozenPv frozen) {

    /**
     * Whether the PV connected and then disconnected for longer than the grace period. A PV that
     * never connected may just not exist, such as a mistyped name, so it doesn't count.
     *
     * @return true if disconnected
     */
    public boolean disconnected() {
      return notConnected != null && state == ChannelMonitor.MonitorState.DISCONNECTED;
    }
  }

  /**
   * Find the PVs to report now.
   *
   * @param monitorMap The monitors, by PV
   * @param detector The frozen PV detector, or null if frozen PV detection is off
   * @param grace How long a PV may be disconnected before it's reported
   * @return The reported PVs, ordered by name
   */
  public static List<UnhealthyPv> current(
      Map<String, ChannelMonitor> monitorMap, FrozenPvDetector detector, Duration grace) {
    return find(
        FrozenPvDetector.viewsOf(monitorMap),
        detector == null ? Map.of() : detector.getFrozen(),
        Instant.now(),
        grace);
  }

  /**
   * Find the reported PVs.
   *
   * @param monitors The monitors, by PV
   * @param frozen The frozen PVs, by PV
   * @param now The current time
   * @param grace How long a PV may be disconnected before it's reported
   * @return The reported PVs, ordered by name
   */
  public static List<UnhealthyPv> find(
      Map<String, FrozenPvDetector.MonitorView> monitors,
      Map<String, FrozenPvDetector.FrozenPv> frozen,
      Instant now,
      Duration grace) {
    Map<String, Duration> notConnected = new HashMap<>();

    for (Map.Entry<String, FrozenPvDetector.MonitorView> entry : monitors.entrySet()) {
      FrozenPvDetector.MonitorView monitor = entry.getValue();

      if (monitor.state() == ChannelMonitor.MonitorState.CONNECTED) {
        continue;
      }

      Duration duration = Duration.between(monitor.stateChanged(), now);

      if (duration.toSeconds() > grace.toSeconds()) {
        notConnected.put(entry.getKey(), duration);
      }
    }

    Set<String> names = new TreeSet<>(notConnected.keySet());
    names.addAll(frozen.keySet());

    List<UnhealthyPv> unhealthy = new ArrayList<>();

    for (String pv : names) {
      FrozenPvDetector.MonitorView monitor = monitors.get(pv);
      unhealthy.add(
          new UnhealthyPv(
              pv, monitor == null ? null : monitor.state(), notConnected.get(pv), frozen.get(pv)));
    }

    return unhealthy;
  }
}
