package org.jlab.epics2web.report;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.jlab.epics2web.epics.ChannelMonitor;
import org.jlab.epics2web.epics.UnhealthyPvs;
import org.jlab.epics2web.websocket.SessionInfo;

/**
 * The disconnected PVs report: the PVs the healthcheck lists, with the clients that monitor them,
 * by PV and by client. Getters rather than records, for JSP.
 */
public final class DisconnectedPvReport {

  /**
   * Times as text, which sorts in time order; formatted here rather than with fmt:formatDate, which
   * falls back to Date.toString when the request carries no locale.
   */
  private static final DateTimeFormatter TIME_FORMAT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

  private final List<Row> rows;
  private final List<ClientGroup> clientGroups;
  private final long disconnectedCount;
  private final long neverConnectedCount;
  private final long frozenCount;

  private DisconnectedPvReport(List<Row> rows, List<ClientGroup> clientGroups) {
    this.rows = rows;
    this.clientGroups = clientGroups;
    this.disconnectedCount = rows.stream().filter(r -> r.pv.disconnected()).count();
    this.neverConnectedCount =
        rows.stream()
            .filter(
                r ->
                    r.pv.notConnected() != null
                        && r.pv.state() == ChannelMonitor.MonitorState.CONNECTING)
            .count();
    this.frozenCount = rows.stream().filter(r -> r.pv.frozen() != null).count();
  }

  /**
   * Build the report.
   *
   * @param unhealthy The PVs the healthcheck lists
   * @param via The server each PV was last reached through, by PV, where known
   * @param clientMap The clients, with the PVs each monitors
   * @param now The current time
   * @return The report
   */
  public static DisconnectedPvReport build(
      List<UnhealthyPvs.UnhealthyPv> unhealthy,
      Map<String, String> via,
      Map<SessionInfo, Set<String>> clientMap,
      Instant now) {
    Map<String, Map<ClientLabel, Integer>> clientsByPv = new HashMap<>();
    Map<ClientLabel, ClientGroup> groups = new LinkedHashMap<>();

    Set<String> names = new TreeSet<>();
    unhealthy.forEach(pv -> names.add(pv.name()));

    for (Map.Entry<SessionInfo, Set<String>> entry : clientMap.entrySet()) {
      SessionInfo session = entry.getKey();
      List<String> pvs = entry.getValue().stream().filter(names::contains).sorted().toList();

      if (pvs.isEmpty()) {
        continue;
      }

      ClientLabel label = ClientLabel.of(session.getName(), session.getIp());

      ClientGroup group = groups.computeIfAbsent(label, ClientGroup::new);
      group.sessions++;
      group.ips.add(session.getIp() == null ? "Unknown" : session.getIp());
      group.pvs.addAll(pvs);

      for (String pv : pvs) {
        clientsByPv.computeIfAbsent(pv, k -> new HashMap<>()).merge(label, 1, Integer::sum);
      }
    }

    List<Row> rows = new ArrayList<>();

    for (UnhealthyPvs.UnhealthyPv pv : unhealthy) {
      List<ClientCount> clients = new ArrayList<>();
      clientsByPv
          .getOrDefault(pv.name(), Map.of())
          .forEach((label, sessions) -> clients.add(new ClientCount(label, sessions)));
      clients.sort(Comparator.comparing(c -> c.label.getText()));
      rows.add(new Row(pv, via.get(pv.name()), clients, now));
    }

    rows.sort(Comparator.comparing(Row::getName));

    List<ClientGroup> clientGroups = new ArrayList<>(groups.values());
    clientGroups.sort(
        Comparator.comparing((ClientGroup g) -> -g.pvs.size())
            .thenComparing(g -> g.label.getText()));

    return new DisconnectedPvReport(rows, clientGroups);
  }

  /**
   * @return The PVs, by name
   */
  public List<Row> getRows() {
    return rows;
  }

  /**
   * @return The clients that monitor reported PVs, those with the most first; clients with the same
   *     label, such as several sessions of one screen, are one group
   */
  public List<ClientGroup> getClientGroups() {
    return clientGroups;
  }

  /**
   * @return The number of PVs that connected and then disconnected
   */
  public long getDisconnectedCount() {
    return disconnectedCount;
  }

  /**
   * @return The number of PVs that never connected
   */
  public long getNeverConnectedCount() {
    return neverConnectedCount;
  }

  /**
   * @return The number of frozen PVs
   */
  public long getFrozenCount() {
    return frozenCount;
  }

  /** A reported PV. */
  public static final class Row {
    private final UnhealthyPvs.UnhealthyPv pv;
    private final String via;
    private final List<ClientCount> clients;
    private final String downSince;

    Row(UnhealthyPvs.UnhealthyPv pv, String via, List<ClientCount> clients, Instant now) {
      this.pv = pv;
      this.via = via;
      this.clients = clients;
      this.downSince = pv.notConnected() == null ? null : formatTime(now.minus(pv.notConnected()));
    }

    public String getName() {
      return pv.name();
    }

    /**
     * @return Disconnected, Never connected, or Frozen; a frozen PV that's also disconnected is
     *     both
     */
    public String getStatus() {
      String status;

      if (pv.notConnected() == null) {
        status = null;
      } else if (pv.state() == ChannelMonitor.MonitorState.CONNECTING) {
        status = "Never connected";
      } else {
        status = "Disconnected";
      }

      if (pv.frozen() != null) {
        status = status == null ? "Frozen" : status + ", frozen";
      }

      return status;
    }

    /**
     * @return Why the PV is frozen, or null if it isn't
     */
    public String getFrozenReason() {
      return pv.frozen() == null ? null : pv.frozen().reason();
    }

    /**
     * @return How long the PV hasn't been connected, such as "2 h 5 min", or null if it's connected
     */
    public String getDownFor() {
      return pv.notConnected() == null ? null : formatDuration(pv.notConnected());
    }

    /**
     * @return When the PV disconnected, or when monitoring began if it never connected, such as
     *     "2026-01-01 12:00:00"; null if it's connected
     */
    public String getDownSince() {
      return downSince;
    }

    /**
     * @return The server the PV was last reached through, such as a CA gateway, or null if it never
     *     connected
     */
    public String getVia() {
      return via;
    }

    /**
     * @return The clients that monitor the PV, by label
     */
    public List<ClientCount> getClients() {
      return clients;
    }
  }

  /** The sessions with one label that monitor a PV. */
  public static final class ClientCount {
    private final ClientLabel label;
    private final int sessions;

    ClientCount(ClientLabel label, int sessions) {
      this.label = label;
      this.sessions = sessions;
    }

    public ClientLabel getLabel() {
      return label;
    }

    public int getSessions() {
      return sessions;
    }
  }

  /** The sessions with one label, and the reported PVs they monitor. */
  public static final class ClientGroup {
    private final ClientLabel label;
    private int sessions;
    private final Set<String> ips = new TreeSet<>();
    private final Set<String> pvs = new TreeSet<>();

    ClientGroup(ClientLabel label) {
      this.label = label;
    }

    public ClientLabel getLabel() {
      return label;
    }

    public int getSessions() {
      return sessions;
    }

    public Set<String> getIps() {
      return ips;
    }

    public Set<String> getPvs() {
      return pvs;
    }
  }

  /**
   * Format a time for people, in the server's time zone, such as "2026-01-01 12:00:00".
   *
   * @param time The time
   * @return The text
   */
  public static String formatTime(Instant time) {
    return TIME_FORMAT.format(time);
  }

  /**
   * Format a duration for people, to the minute: "45 s", "12 min", "3 h 5 min", or "2 d 4 h".
   *
   * @param duration The duration
   * @return The text
   */
  static String formatDuration(Duration duration) {
    long seconds = duration.toSeconds();

    if (seconds < 60) {
      return seconds + " s";
    }

    long minutes = seconds / 60;

    if (minutes < 60) {
      return minutes + " min";
    }

    long hours = minutes / 60;

    if (hours < 24) {
      return hours + " h " + (minutes % 60) + " min";
    }

    return (hours / 24) + " d " + (hours % 24) + " h";
  }
}
