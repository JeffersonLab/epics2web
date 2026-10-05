package org.jlab.epics2web.controller;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jlab.epics2web.Application;
import org.jlab.epics2web.epics.ChannelManager;
import org.jlab.epics2web.epics.ChannelMonitor;
import org.jlab.epics2web.epics.UnhealthyPvs;
import org.jlab.epics2web.report.DisconnectedPvReport;
import org.jlab.epics2web.report.ServerNames;
import org.jlab.epics2web.websocket.WebSocketSessionManager;

/**
 * Controller for the Disconnected PVs report: the PVs the healthcheck lists, with the clients that
 * monitor them, such as WEDM screens, and the server each was last reached through. The page looks
 * up each PV's IOC in the browser.
 */
@WebServlet(
    name = "DisconnectedPvs",
    urlPatterns = {"/disconnected-pvs"})
public class DisconnectedPvs extends HttpServlet {

  private final ChannelManager channelManager = Application.channelManager;
  private final WebSocketSessionManager sessionManager = Application.sessionManager;

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

    Map<String, ChannelMonitor> monitorMap = channelManager.getMonitorMap();

    List<UnhealthyPvs.UnhealthyPv> unhealthy =
        UnhealthyPvs.current(
            monitorMap,
            Application.frozenPvDetector,
            Duration.ofSeconds(Application.HEALTHCHECK_GRACE_SECONDS));

    Map<String, String> via = new HashMap<>();

    for (UnhealthyPvs.UnhealthyPv pv : unhealthy) {
      ChannelMonitor monitor = monitorMap.get(pv.name());
      InetSocketAddress server = monitor == null ? null : monitor.getLastServer();

      if (server != null) {
        via.put(pv.name(), ServerNames.describe(server));
      }
    }

    Instant now = Instant.now();

    request.setAttribute(
        "report", DisconnectedPvReport.build(unhealthy, via, sessionManager.getClientMap(), now));
    request.setAttribute("generated", DisconnectedPvReport.formatTime(now));
    request.setAttribute("graceSeconds", Application.HEALTHCHECK_GRACE_SECONDS);
    request.setAttribute("myqueryUrl", Application.MYQUERY_URL);
    request.setAttribute("myqueryDeployment", Application.MYQUERY_DEPLOYMENT);

    request.getRequestDispatcher("/WEB-INF/views/disconnected-pvs.jsp").forward(request, response);
  }
}
