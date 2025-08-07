package org.jlab.epics2web.controller;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import org.jlab.epics2web.Application;
import org.jlab.epics2web.epics.ChannelManager;
import org.jlab.epics2web.epics.ChannelMonitor;
import org.jlab.epics2web.websocket.SessionInfo;
import org.jlab.epics2web.websocket.WebSocketSessionManager;

/**
 * Controller for the Console page.
 *
 * @author slominskir
 */
@WebServlet(
    name = "WebConsole",
    urlPatterns = {"/console"})
public class WebConsole extends HttpServlet {

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
    Map<SessionInfo, Set<String>> clientMap = sessionManager.getClientMap();

    request.setAttribute("monitorMap", monitorMap);
    request.setAttribute("clientMap", clientMap);

    request.getRequestDispatcher("/WEB-INF/views/console.jsp").forward(request, response);
  }
}
