package org.jlab.epics2web.epics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.jlab.epics2web.epics.ChannelMonitor.MonitorState;
import org.jlab.epics2web.epics.FrozenPvDetector.MonitorView;
import org.junit.Test;

/**
 * Tests FrozenPvDetector's decisions with a controlled clock, monitors and probes. With a 10 s
 * interval: quiet after 60 s, probes last 60 s, re-probe after 300 s, evidence counts after 5 s.
 */
public class FrozenPvDetectorTest {

  private static final Duration GRACE = Duration.ofSeconds(30);
  private static final Duration INTERVAL = Duration.ofSeconds(10);

  private final MutableClock clock = new MutableClock();
  private final Map<String, MonitorView> monitors = new HashMap<>();
  private final Map<String, FakeProbe> probes = new HashMap<>();
  private final FrozenPvDetector detector =
      new FrozenPvDetector(() -> Map.copyOf(monitors), this::startProbe, clock, GRACE, INTERVAL, 2);

  /** A PV that used to update, went quiet, and changed for the probe but not the monitor. */
  @Test
  public void quietPvThatChangedForProbeIsFrozen() {
    connected("pv1", 5);
    advance(60);
    detector.check();
    assertEquals(Set.of("pv1"), probes.keySet());

    probes.get("pv1").connectedAt = clock.instant();
    probes.get("pv1").firstChangeAt = clock.instant();
    advance(4); // Evidence not yet 5 s old: the monitor may still be about to get it
    detector.check();
    assertTrue(detector.getFrozen().isEmpty());

    advance(1);
    detector.check();
    assertEquals(Set.of("pv1"), detector.getFrozen().keySet());
    assertTrue(probes.get("pv1").closed);
  }

  @Test
  public void frozenPvRecoversWhenItsMonitorGetsAnUpdate() {
    quietPvThatChangedForProbeIsFrozen();

    advance(10);
    connected("pv1", 6);
    detector.check();

    assertTrue(detector.getFrozen().isEmpty());
  }

  /** A quiet PV that doesn't change for the probe either just isn't changing. */
  @Test
  public void quietPvThatDoesNotChangeIsNotFrozen() {
    connected("pv1", 5);
    advance(60);
    detector.check();
    probes.get("pv1").connectedAt = clock.instant();

    advance(60);
    detector.check();

    assertTrue(detector.getFrozen().isEmpty());
    assertTrue("Inconclusive probe left open", probes.get("pv1").closed);

    probes.clear();
    advance(200); // 260 s since it was probed
    detector.check();
    assertTrue("Probed again too soon", probes.isEmpty());

    advance(40);
    detector.check();
    assertEquals(Set.of("pv1"), probes.keySet());
  }

  @Test
  public void monitorThatGetsAnUpdateDuringProbeIsNotFrozen() {
    connected("pv1", 5);
    advance(60);
    detector.check();

    connected("pv1", 6);
    probes.get("pv1").connectedAt = clock.instant();
    probes.get("pv1").firstChangeAt = clock.instant();
    advance(10);
    detector.check();

    assertTrue(detector.getFrozen().isEmpty());
    assertTrue(probes.get("pv1").closed);
  }

  /** Only PVs that changed since subscribing can show updates going missing. */
  @Test
  public void pvThatNeverChangedIsNotProbed() {
    connected("pv1", 1);
    advance(600);
    detector.check();

    assertTrue(probes.isEmpty());
  }

  @Test
  public void disconnectedPvTheIocServesIsFrozen() {
    monitors.put("pv1", new MonitorView(MonitorState.DISCONNECTED, clock.instant(), null, 3));
    advance(29);
    detector.check();
    assertTrue("Probed within the grace period", probes.isEmpty());

    advance(1);
    detector.check();
    probes.get("pv1").connectedAt = clock.instant();
    advance(5);
    detector.check();

    assertEquals(Set.of("pv1"), detector.getFrozen().keySet());
  }

  @Test
  public void neverConnectedPvTheIocServesIsFrozen() {
    monitors.put("pv1", new MonitorView(MonitorState.CONNECTING, clock.instant(), null, 0));
    advance(30);
    detector.check();
    probes.get("pv1").connectedAt = clock.instant();
    advance(5);
    detector.check();

    assertEquals(Set.of("pv1"), detector.getFrozen().keySet());
  }

  /** If the probe can't reach the PV either, the IOC is down or the PV doesn't exist. */
  @Test
  public void disconnectedPvTheIocDoesNotServeIsNotFrozen() {
    monitors.put("pv1", new MonitorView(MonitorState.DISCONNECTED, clock.instant(), null, 3));
    advance(30);
    detector.check();

    advance(60);
    detector.check();

    assertTrue(detector.getFrozen().isEmpty());
    assertTrue(probes.get("pv1").closed);
  }

  @Test
  public void atMostMaxProbesAtOnce() {
    connected("pv1", 5);
    connected("pv2", 5);
    connected("pv3", 5);
    advance(60);

    detector.check();

    assertEquals(2, probes.size());
  }

  @Test
  public void pvNoLongerMonitoredIsDropped() {
    quietPvThatChangedForProbeIsFrozen();
    connected("pv2", 5);
    advance(60);
    detector.check(); // Starts probing pv2

    monitors.clear();
    detector.check();

    assertTrue(detector.getFrozen().isEmpty());
    assertTrue(probes.get("pv2").closed);
  }

  @Test
  public void closeClosesOpenProbes() {
    connected("pv1", 5);
    advance(60);
    detector.check();

    detector.close();

    assertTrue(probes.get("pv1").closed);
    assertFalse(detector.getFrozen().containsKey("pv1"));
  }

  /** A connected monitor whose latest update is now. */
  private void connected(String pv, long updateCount) {
    monitors.put(
        pv, new MonitorView(MonitorState.CONNECTED, Instant.EPOCH, clock.instant(), updateCount));
  }

  private void advance(long seconds) {
    clock.now = clock.now.plusSeconds(seconds);
  }

  private FrozenPvDetector.Probe startProbe(String pv) {
    FakeProbe probe = new FakeProbe();
    probes.put(pv, probe);
    return probe;
  }

  private static class FakeProbe implements FrozenPvDetector.Probe {
    Instant connectedAt;
    Instant firstChangeAt;
    boolean closed;

    @Override
    public Instant connectedAt() {
      return connectedAt;
    }

    @Override
    public Instant firstChangeAt() {
      return firstChangeAt;
    }

    @Override
    public void close() {
      closed = true;
    }
  }

  private static class MutableClock extends Clock {
    Instant now = Instant.parse("2026-10-04T12:00:00Z");

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
