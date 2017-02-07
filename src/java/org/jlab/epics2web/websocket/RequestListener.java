package org.jlab.epics2web.websocket;

import javax.servlet.ServletRequestEvent;
import javax.servlet.ServletRequestListener;
import javax.servlet.annotation.WebListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

/**
 * Stores the remote address in the user's session. This is necessary since the Java web socket API
 * does not expose the remote address. Note: some implementations do (Tomcat does not, GlassFish
 * does).
 *
 * @author ryans
 */
@WebListener
public class RequestListener implements ServletRequestListener {

    @Override
    public void requestDestroyed(ServletRequestEvent sre) {

    }

    @Override
    public void requestInitialized(ServletRequestEvent sre) {
        HttpServletRequest request = (HttpServletRequest) sre.getServletRequest();

        HttpSession session = request.getSession();

        session.setAttribute("remoteAddr", request.getRemoteAddr());
    }

}
