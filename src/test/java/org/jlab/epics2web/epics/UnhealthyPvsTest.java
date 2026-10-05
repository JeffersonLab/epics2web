package org.jlab.epics2web.epics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jlab.epics2web.epics.ChannelMonitor.MonitorState;
import org.junit.Test;

public class UnhealthyPvsTest {

  private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");
  private static final Duration GRACE = Duration.ofSeconds(30);

  private static FrozenPvDetector.MonitorView view(MonitorState state, long secondsAgo) {
    return new FrozenPvDetector.MonitorView(state, NOW.minusSeconds(secondsAgo), null, 0);
  }

  @Test
  public void reportsPvsNotConnectedForLongerThanTheGracePeriod() {
    List<UnhealthyPvs.UnhealthyPv> unhealthy =
        UnhealthyPvs.find(
            Map.of(
                "b:disconnected", view(MonitorState.DISCONNECTED, 120),
                "a:never", view(MonitorState.CONNECTING, 60),
                "c:recent", view(MonitorState.DISCONNECTED, 30),
                "d:connected", view(MonitorState.CONNECTED, 600)),
            Map.of(),
            NOW,
            GRACE);

    assertEquals(2, unhealthy.size());

    UnhealthyPvs.UnhealthyPv never = unhealthy.get(0);
    assertEquals("a:never", never.name());
    assertEquals(MonitorState.CONNECTING, never.state());
    assertEquals(Duration.ofSeconds(60), never.notConnected());
    assertFalse("Never connected, so it may not exist", never.disconnected());

    UnhealthyPvs.UnhealthyPv disconnected = unhealthy.get(1);
    assertEquals("b:disconnected", disconnected.name());
    assertEquals(Duration.ofSeconds(120), disconnected.notConnected());
    assertTrue(disconnected.disconnected());
    assertNull(disconnected.frozen());
  }

  @Test
  public void reportsFrozenPvsWhetherOrNotConnected() {
    FrozenPvDetector.FrozenPv quiet = new FrozenPvDetector.FrozenPv(NOW, "missed a change");
    FrozenPvDetector.FrozenPv gone = new FrozenPvDetector.FrozenPv(NOW, "independent connected");

    List<UnhealthyPvs.UnhealthyPv> unhealthy =
        UnhealthyPvs.find(
            Map.of(
                "quiet", view(MonitorState.CONNECTED, 600),
                "down", view(MonitorState.DISCONNECTED, 120)),
            Map.of("quiet", quiet, "down", gone, "removed", quiet),
            NOW,
            GRACE);

    assertEquals(
        List.of("down", "quiet", "removed"), unhealthy.stream().map(p -> p.name()).toList());

    UnhealthyPvs.UnhealthyPv down = unhealthy.get(0);
    assertEquals(gone, down.frozen());
    assertTrue(down.disconnected());

    UnhealthyPvs.UnhealthyPv connected = unhealthy.get(1);
    assertEquals(MonitorState.CONNECTED, connected.state());
    assertNull(connected.notConnected());
    assertFalse(connected.disconnected());

    UnhealthyPvs.UnhealthyPv removed = unhealthy.get(2);
    assertNull("No longer monitored", removed.state());
    assertNull(removed.notConnected());
  }
}
