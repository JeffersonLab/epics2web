package org.jlab.epics2web.controller;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObjectBuilder;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jlab.epics2web.Application;
import org.jlab.epics2web.epics.ChannelManager;
import org.jlab.epics2web.epics.ChannelMonitor;
import org.jlab.epics2web.epics.FrozenPvDetector;

/**
 * Controller for Healthcheck page. Return 200 OK, for healthy Return 503 Service Unavailable for
 * unhealthy (or ANYTHING non 200-299).
 *
 * @author slominskir
 */
@WebServlet(
    name = "Healthcheck",
    urlPatterns = {"/healthcheck"})
public class Healthcheck extends HttpServlet {

  private final ChannelManager channelManager = Application.channelManager;
  private static final Logger LOGGER = Logger.getLogger(Healthcheck.class.getName());

  /**
   * Handles the HTTP <code>GET</code> method.
   *
   * @param request servlet request
   * @param response servlet response
   * @throws ServletException if a servlet-specific error occurs
   * @throws IOException if an I/O error occurs
   */
  @Override
  protected void doGet(HttpServletRequest request, HttpServletResponse response)
      throws ServletException, IOException {

    // Strict mode answers 503 when a PV is reported, for monitoring that alerts on PVs. The default
    // answers 200 whenever the server is up, for load balancers: an IOC being down affects every
    // instance alike, and restarting the server doesn't bring it back.
    boolean strict = isSet(request.getParameter("strict"));

    // Frozen mode answers 503 when a PV is frozen: its monitor stopped working while the IOC still
    // serves it, which a restart is expected to fix. See FrozenPvDetector.
    boolean frozenMode = isSet(request.getParameter("frozen"));

    boolean healthy = true;

    Map<String, ChannelMonitor> monitorMap = channelManager.getMonitorMap();

    Instant now = Instant.now();

    Map<String, JsonObjectBuilder> entries = new LinkedHashMap<>();

    for (Map.Entry<String, ChannelMonitor> entry : monitorMap.entrySet()) {
      String pv = entry.getKey();
      ChannelMonitor monitor = entry.getValue();
      ChannelMonitor.MonitorState state = monitor.getState();

      if (state == ChannelMonitor.MonitorState.CONNECTED) {
        continue;
      }

      // Time since the PV disconnected, or since monitoring began if it never connected
      Duration notConnected = Duration.between(monitor.getStateChanged(), now);

      if (notConnected.toSeconds() > Application.HEALTHCHECK_GRACE_SECONDS) {
        // A PV that never connected may just not exist, such as a mistyped name, so it's listed
        // but doesn't make strict mode fail
        if (state == ChannelMonitor.MonitorState.DISCONNECTED) {
          healthy = false;
        }

        JsonObjectBuilder unhealthyChannel = Json.createObjectBuilder();
        unhealthyChannel.add("name", pv);
        unhealthyChannel.add("state", state.name());
        unhealthyChannel.add(
            "disconnected_minutes", String.format("%.1f", notConnected.toSeconds() / 60.0));
        entries.put(pv, unhealthyChannel);
      }
    }

    Map<String, FrozenPvDetector.FrozenPv> frozen =
        Application.frozenPvDetector == null ? Map.of() : Application.frozenPvDetector.getFrozen();

    for (Map.Entry<String, FrozenPvDetector.FrozenPv> entry : frozen.entrySet()) {
      String pv = entry.getKey();
      ChannelMonitor monitor = monitorMap.get(pv);
      JsonObjectBuilder frozenChannel =
          entries.computeIfAbsent(
              pv,
              k ->
                  Json.createObjectBuilder()
                      .add("name", pv)
                      .add("state", monitor == null ? "UNKNOWN" : monitor.getState().name()));
      frozenChannel.add("frozen", true);
      frozenChannel.add(
          "frozen_minutes",
          String.format(
              "%.1f", Duration.between(entry.getValue().since(), now).toSeconds() / 60.0));
      frozenChannel.add("frozen_reason", entry.getValue().reason());
    }

    JsonArrayBuilder unhealthyChannelArray = Json.createArrayBuilder();
    entries.values().forEach(unhealthyChannelArray::add);

    response.setContentType("application/json");

    PrintWriter pw = response.getWriter();

    response.setStatus(HttpServletResponse.SC_OK);

    if ((strict && !healthy) || (frozenMode && !frozen.isEmpty())) {
      response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
    }

    String jsonStr = unhealthyChannelArray.build().toString();

    pw.write(jsonStr);

    pw.flush();

    boolean error = pw.checkError();

    if (error) {
      LOGGER.log(Level.SEVERE, "PrintWriter Error");
    }
  }

  private static boolean isSet(String param) {
    return param != null && !"false".equalsIgnoreCase(param);
  }
}
