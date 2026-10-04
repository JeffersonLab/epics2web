package org.jlab.epics2web.websocket;

import jakarta.servlet.ServletRequestEvent;
import jakarta.servlet.ServletRequestListener;
import jakarta.servlet.annotation.WebListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.Locale;

/**
 * Stores the remote address in the user's session for WebSocket handshakes. This is necessary since
 * the Java web socket API does not expose the remote address. Note: some implementations do (Tomcat
 * does not, GlassFish does).
 *
 * <p>Only handshakes get a session: a session for every request would keep one per request, for the
 * session timeout, for clients without cookies such as scripts and health probes.
 *
 * @author slominskir
 */
@WebListener
public class RequestListener implements ServletRequestListener {

  @Override
  public void requestDestroyed(ServletRequestEvent sre) {}

  @Override
  public void requestInitialized(ServletRequestEvent sre) {
    HttpServletRequest request = (HttpServletRequest) sre.getServletRequest();

    if (isWebSocketHandshake(request)) {
      HttpSession session = request.getSession();

      session.setAttribute("remoteAddr", request.getRemoteAddr());
    }
  }

  static boolean isWebSocketHandshake(HttpServletRequest request) {
    String upgrade = request.getHeader("Upgrade");
    return upgrade != null && upgrade.toLowerCase(Locale.ROOT).contains("websocket");
  }
}
