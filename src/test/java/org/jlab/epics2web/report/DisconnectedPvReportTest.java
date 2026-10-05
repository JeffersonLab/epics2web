package org.jlab.epics2web.report;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jlab.epics2web.epics.ChannelMonitor.MonitorState;
import org.jlab.epics2web.epics.FrozenPvDetector;
import org.jlab.epics2web.epics.UnhealthyPvs.UnhealthyPv;
import org.jlab.epics2web.websocket.SessionInfo;
import org.junit.Test;

public class DisconnectedPvReportTest {

  private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");
  private static final String SCREEN = "https://example.org/wedm/screen?edl=/cs/a.edl";

  private static final UnhealthyPv DOWN =
      new UnhealthyPv("b:down", MonitorState.DISCONNECTED, Duration.ofMinutes(90), null);
  private static final UnhealthyPv NEVER =
      new UnhealthyPv("a:never", MonitorState.CONNECTING, Duration.ofSeconds(45), null);
  private static final UnhealthyPv FROZEN =
      new UnhealthyPv(
          "c:frozen",
          MonitorState.CONNECTED,
          null,
          new FrozenPvDetector.FrozenPv(NOW, "missed a change"));

  private static SessionInfo session(String id, String ip, String name) {
    return new SessionInfo(id, ip, name, "agent", 0);
  }

  private static DisconnectedPvReport report() {
    Map<SessionInfo, Set<String>> clients = new HashMap<>();
    clients.put(session("1", "10.0.0.1", SCREEN), Set.of("b:down", "c:frozen", "ok"));
    clients.put(session("2", "10.0.0.2", SCREEN), Set.of("b:down"));
    clients.put(session("3", "10.0.0.3", ""), Set.of("a:never", "b:down"));
    clients.put(session("4", "10.0.0.4", "healthy client"), Set.of("ok"));

    return DisconnectedPvReport.build(
        List.of(DOWN, NEVER, FROZEN), Map.of("b:down", "gateway:5064"), clients, NOW);
  }

  @Test
  public void rowsArePvsByNameWithTheirClients() {
    List<DisconnectedPvReport.Row> rows = report().getRows();

    assertEquals(
        List.of("a:never", "b:down", "c:frozen"),
        rows.stream().map(DisconnectedPvReport.Row::getName).toList());

    DisconnectedPvReport.Row down = rows.get(1);
    assertEquals("Disconnected", down.getStatus());
    assertEquals("1 h 30 min", down.getDownFor());
    assertEquals(
        DisconnectedPvReport.formatTime(NOW.minus(Duration.ofMinutes(90))), down.getDownSince());
    assertEquals("gateway:5064", down.getVia());
    assertNull(down.getFrozenReason());

    // Sorted by label text; two sessions of the screen are one entry
    List<DisconnectedPvReport.ClientCount> clients = down.getClients();
    assertEquals(2, clients.size());
    assertEquals("/cs/a.edl", clients.get(0).getLabel().getText());
    assertEquals(2, clients.get(0).getSessions());
    assertEquals("Unnamed client at 10.0.0.3", clients.get(1).getLabel().getText());
    assertEquals(1, clients.get(1).getSessions());

    DisconnectedPvReport.Row never = rows.get(0);
    assertEquals("Never connected", never.getStatus());
    assertEquals("45 s", never.getDownFor());
    assertNull(never.getVia());

    DisconnectedPvReport.Row frozen = rows.get(2);
    assertEquals("Frozen", frozen.getStatus());
    assertEquals("missed a change", frozen.getFrozenReason());
    assertNull(frozen.getDownFor());
    assertNull(frozen.getDownSince());
  }

  @Test
  public void clientGroupsAreByLabelWithTheMostPvsFirst() {
    List<DisconnectedPvReport.ClientGroup> groups = report().getClientGroups();

    // The healthy client monitors no reported PV
    assertEquals(2, groups.size());

    DisconnectedPvReport.ClientGroup screen = groups.get(0);
    assertEquals("/cs/a.edl", screen.getLabel().getText());
    assertEquals(2, screen.getSessions());
    assertEquals(Set.of("10.0.0.1", "10.0.0.2"), screen.getIps());
    assertEquals(List.of("b:down", "c:frozen"), List.copyOf(screen.getPvs()));

    DisconnectedPvReport.ClientGroup unnamed = groups.get(1);
    assertEquals(1, unnamed.getSessions());
    assertEquals(List.of("a:never", "b:down"), List.copyOf(unnamed.getPvs()));
  }

  @Test
  public void countsByKind() {
    DisconnectedPvReport report = report();

    assertEquals(1, report.getDisconnectedCount());
    assertEquals(1, report.getNeverConnectedCount());
    assertEquals(1, report.getFrozenCount());
  }

  @Test
  public void frozenPvThatIsAlsoDisconnectedIsBoth() {
    UnhealthyPv both =
        new UnhealthyPv(
            "pv",
            MonitorState.DISCONNECTED,
            Duration.ofMinutes(5),
            new FrozenPvDetector.FrozenPv(NOW, "independent connected"));

    DisconnectedPvReport report =
        DisconnectedPvReport.build(List.of(both), Map.of(), Map.of(), NOW);

    assertEquals("Disconnected, frozen", report.getRows().get(0).getStatus());
    assertEquals(0, report.getClientGroups().size());
  }

  @Test
  public void timesAreFormattedToSortAsText() {
    assertTrue(
        DisconnectedPvReport.formatTime(NOW).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"));
    assertTrue(
        DisconnectedPvReport.formatTime(NOW.minusSeconds(1))
                .compareTo(DisconnectedPvReport.formatTime(NOW))
            < 0);
  }

  @Test
  public void durationsAreFormattedToTheMinute() {
    assertEquals("0 s", DisconnectedPvReport.formatDuration(Duration.ZERO));
    assertEquals("59 s", DisconnectedPvReport.formatDuration(Duration.ofSeconds(59)));
    assertEquals("1 min", DisconnectedPvReport.formatDuration(Duration.ofSeconds(119)));
    assertEquals("59 min", DisconnectedPvReport.formatDuration(Duration.ofMinutes(59)));
    assertEquals("1 h 0 min", DisconnectedPvReport.formatDuration(Duration.ofMinutes(60)));
    assertEquals("23 h 59 min", DisconnectedPvReport.formatDuration(Duration.ofMinutes(1439)));
    assertEquals("2 d 4 h", DisconnectedPvReport.formatDuration(Duration.ofHours(52)));
  }
}
