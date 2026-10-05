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
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jlab.epics2web.Application;
import org.jlab.epics2web.epics.ChannelManager;
import org.jlab.epics2web.epics.UnhealthyPvs;

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

    List<UnhealthyPvs.UnhealthyPv> unhealthy =
        UnhealthyPvs.current(
            channelManager.getMonitorMap(),
            Application.frozenPvDetector,
            Duration.ofSeconds(Application.HEALTHCHECK_GRACE_SECONDS));

    Instant now = Instant.now();
    boolean healthy = true;
    boolean anyFrozen = false;

    JsonArrayBuilder unhealthyChannelArray = Json.createArrayBuilder();

    for (UnhealthyPvs.UnhealthyPv pv : unhealthy) {
      JsonObjectBuilder channel = Json.createObjectBuilder();
      channel.add("name", pv.name());
      channel.add("state", pv.state() == null ? "UNKNOWN" : pv.state().name());

      if (pv.notConnected() != null) {
        channel.add("disconnected_minutes", minutes(pv.notConnected()));
      }

      if (pv.disconnected()) {
        healthy = false;
      }

      if (pv.frozen() != null) {
        anyFrozen = true;
        channel.add("frozen", true);
        channel.add("frozen_minutes", minutes(Duration.between(pv.frozen().since(), now)));
        channel.add("frozen_reason", pv.frozen().reason());
      }

      unhealthyChannelArray.add(channel);
    }

    response.setContentType("application/json");

    PrintWriter pw = response.getWriter();

    response.setStatus(HttpServletResponse.SC_OK);

    if ((strict && !healthy) || (frozenMode && anyFrozen)) {
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

  private static String minutes(Duration duration) {
    return String.format("%.1f", duration.toSeconds() / 60.0);
  }

  private static boolean isSet(String param) {
    return param != null && !"false".equalsIgnoreCase(param);
  }
}
