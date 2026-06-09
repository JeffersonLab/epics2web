package org.jlab.epics2web.controller;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObjectBuilder;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jlab.epics2web.Application;
import org.jlab.epics2web.epics.ChannelManager;
import org.jlab.epics2web.epics.ChannelMonitor;

import java.io.IOException;
import java.io.PrintWriter;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Controller for Healthcheck page.
 * Return 200 OK, for healthy
 * Return 503 Service Unavailable for unhealthy (or ANYTHING non 200-299).
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

    boolean healthy = true;

    Map<String, ChannelMonitor> monitorMap = channelManager.getMonitorMap();

    Instant now = Instant.now();

    JsonArrayBuilder unhealthyChannelArray = Json.createArrayBuilder();

    for(Map.Entry<String, ChannelMonitor> entry : monitorMap.entrySet()) {
      String pv = entry.getKey();
      ChannelMonitor monitor = entry.getValue();

      // If never an update, then we assume PV doesn't exist.  Might miss some cases.  Better than nothing health check!
      if(monitor.getLastTimestamp() != null) {
        Instant lastTimestamp = monitor.getLastTimestamp().toInstant();
        Duration duration = Duration.between(now, lastTimestamp);
        long differenceInSeconds = Math.abs(duration.toSeconds());

        if (monitor.getState() != ChannelMonitor.MonitorState.CONNECTED && (differenceInSeconds > 30)) {
          healthy = false;
          JsonObjectBuilder unhealthyChannel = Json.createObjectBuilder();
          unhealthyChannel.add("name", pv);
          unhealthyChannel.add("disconnected_minutes", String.format("%.1f", differenceInSeconds / 60.0));
          unhealthyChannelArray.add(unhealthyChannel);
        }
      }
    }

    response.setContentType("application/json");

    PrintWriter pw = response.getWriter();

    response.setStatus(HttpServletResponse.SC_OK);

    if (!healthy) {
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
}
