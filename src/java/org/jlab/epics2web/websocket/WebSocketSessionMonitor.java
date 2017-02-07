package org.jlab.epics2web.websocket;

import gov.aps.jca.dbr.DBR;
import gov.aps.jca.dbr.DBRType;
import java.util.logging.Logger;
import javax.websocket.Session;
import org.jlab.epics2web.epics.PvListener;

/**
 * Wires a WebSocket session to an EPICS PV monitor.
 * 
 * @author ryans
 */
public class WebSocketSessionMonitor implements PvListener {

    private static final Logger LOGGER = Logger.getLogger(WebSocketSessionMonitor.class.getName());

    private final Session session;
    private final WebSocketSessionManager manager;

    /**
     * Create a new WebSocketSessionMonitor.
     * 
     * @param session The web socket session
     * @param manager The session manager
     */
    public WebSocketSessionMonitor(Session session, WebSocketSessionManager manager) {
        this.session = session;
        this.manager = manager;
    }

    @Override
    public void notifyPvInfo(String pv, boolean couldConnect, DBRType type, Integer count,
            String[] enumLabels) {
        manager.sendInfo(session, pv, couldConnect, type, count, enumLabels);
    }

    @Override
    public void notifyPvUpdate(String pv, DBR dbr) {
        manager.sendUpdate(session, pv, dbr);
    }
}
