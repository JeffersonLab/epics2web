package org.jlab.epics2web.controller;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.websocket.Session;
import org.jlab.epics2web.Application;
import org.jlab.epics2web.websocket.WebSocketSessionManager;
import org.jlab.epics2web.epics.ChannelMonitor;
import org.jlab.epics2web.epics.ChannelMonitorManager;

/**
 * Controller for the Console page.
 * 
 * @author ryans
 */
@WebServlet(name = "WebConsole", urlPatterns = {"/console"})
public class WebConsole extends HttpServlet {

    private final ChannelMonitorManager channelManager = Application.channelManager;
    private final WebSocketSessionManager sessionManager = Application.sessionManager;
    
    /**
     * Handles the HTTP
     * <code>GET</code> method.
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
        Map<Session, Set<String>> clientMap = sessionManager.getClientMap();
        
        request.setAttribute("monitorMap", monitorMap);
        request.setAttribute("clientMap", clientMap);
        
        request.getRequestDispatcher("/WEB-INF/views/console.jsp").forward(request, response);
    }
}
