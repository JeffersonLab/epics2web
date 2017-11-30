package org.jlab.epics2web.websocket;

import gov.aps.jca.dbr.DBR;
import gov.aps.jca.dbr.DBRType;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonString;
import javax.json.JsonValue;
import javax.websocket.Session;
import org.jlab.epics2web.Application;
import org.jlab.epics2web.epics.PvListener;

/**
 * Manages web socket sessions and ties them to channel access monitors.
 *
 * @author ryans
 */
public class WebSocketSessionManager {

    private static final Logger LOGGER = Logger.getLogger(WebSocketSessionManager.class.getName());

    /*ConcurrentHashMap provides thread safety on map of listeners*/
    final Map<Session, WebSocketSessionMonitor> listenerMap = new ConcurrentHashMap<>();

    /**
     * Send a pong reply. This is generally done in response to a client ping.
     *
     * @param session The web socket session
     * @throws IOException If unable to send the message.
     */
    public void sendPong(Session session) throws IOException {
        JsonObjectBuilder objBuilder = Json.createObjectBuilder().add("type", "pong");
        JsonObject obj = objBuilder.build();
        String msg = obj.toString();
        this.send(session, "pong", msg);
    }

    public void purgeStaleSessions() {
        for (Session s : listenerMap.keySet()) {
            purgeIfStale(s);
        }
    }

    public void purgeIfStale(Session s) {
        Date lastUpdated = (Date) s.getUserProperties().get("lastUpdated");
        boolean expired = true;

        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.MINUTE, -1); // Stale if no interaction for 1 minute

        if (lastUpdated != null && lastUpdated.before(cal.getTime())) {
            expired = true;
        }

        if (!s.isOpen() || expired) {
            LOGGER.log(Level.INFO, "Expiring session: {0}", s.getId());
            removeClient(s);
            if (s.isOpen()) {
                try {
                    s.close();
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Unable to close expired session", e);
                }
            }
        }
    }

    public void pingAllSessions() {
        for (Session s : listenerMap.keySet()) {
            try {
                sendWsPing(s);
            } catch (IllegalArgumentException | IOException e) {
                LOGGER.log(Level.WARNING, "Unable to send WS ping", e);
                removeClient(s);

                if (s.isOpen()) {
                    try {
                        s.close();
                    } catch (IOException e2) {
                        LOGGER.log(Level.WARNING, "Unable to close bad session", e2);
                    }
                }
            }
        }
    }

    public void sendWsPing(Session session) throws IllegalArgumentException, IOException {
        if (session.isOpen()) {
            synchronized (session) {
                session.getBasicRemote().sendPing(ByteBuffer.allocate(0));
            }
        } else {
            LOGGER.log(Level.WARNING, "session is closed: {0}", session);
        }
    }

    /**
     * Parse a client request to extract a set of PVs.
     *
     * @param pvs The JSON array containing PVs
     * @return The set of PVs
     */
    public Set<String> getPvSetFromJson(JsonArray pvs) {
        Set<String> pvSet = new HashSet<>();

        for (JsonValue v : pvs) {
            if (v.getValueType() == JsonValue.ValueType.STRING) {
                String pv = ((JsonString) v).getString();
                pvSet.add(pv);
            } else {
                LOGGER.log(Level.WARNING, "PV not a string: {0}", v);
            }
        }

        return pvSet;
    }

    /**
     * Record the fact that this session is still active as of "now".
     *
     * @param session The Websocket Session
     */
    public void recordInteractionDate(Session session) {
        if (session != null && session.isOpen()) {
            Map<String, Object> properties = session.getUserProperties();
            properties.put("lastUpdated", new Date());
        }
    }

    /**
     * Add a new client to the manager's list of clients.
     *
     * @param session The session (client) to manage
     */
    public void addClient(Session session) {
        WebSocketSessionMonitor listener = getListener(session);

        Application.channelManager.addListener(listener);
    }

    /**
     * Remove a client from the manager's list of clients.
     *
     * @param session The session (client) to remove
     */
    public void removeClient(Session session) {
        WebSocketSessionMonitor listener = listenerMap.get(session);

        if (listener != null) {
            listenerMap.remove(session);

            Application.channelManager.removeListener(listener);
        }
    }

    /**
     * Monitor the provided set of PVs for the specified client.
     *
     * @param session The client session
     * @param pvSet The set of PVs
     */
    public void addPvs(Session session, Set<String> pvSet) {
        WebSocketSessionMonitor listener = getListener(session);

        Application.channelManager.addPvs(listener, pvSet);
    }

    /**
     * Stop monitoring the provided PVs for the specified client.
     *
     * @param session The client session
     * @param pvSet The set of PVs
     */
    public void clearPvs(Session session, Set<String> pvSet) {
        WebSocketSessionMonitor listener = getListener(session);

        Application.channelManager.clearPvs(listener, pvSet);
    }

    /**
     * Get a map of sessions to PVs.
     *
     * @return The map
     */
    public Map<Session, Set<String>> getClientMap() {
        Map<PvListener, Set<String>> pvMap = Application.channelManager.getClientMap();
        Map<Session, Set<String>> clientMap = new HashMap<>();

        for (Session session : listenerMap.keySet()) {
            WebSocketSessionMonitor listener = listenerMap.get(session);
            Set<String> pvSet = pvMap.get(listener);
            clientMap.put(session, pvSet);
        }

        return clientMap;
    }

    public Set<Session> toSet() {
        return new HashSet<>(listenerMap.keySet());
    }

    private WebSocketSessionMonitor getListener(Session session) {
        WebSocketSessionMonitor listener = listenerMap.get(session);

        if (listener == null) {
            listener = new WebSocketSessionMonitor(session, this);
            listenerMap.put(session, listener);
        }

        return listener;
    }

    /**
     * Notification of PV metadata sent after registering a PV with a
     * ChannelMonitor.
     *
     * @param session The client
     * @param pv The PV that was registered
     * @param couldConnect true if the channel connected, false otherwise
     * @param type The EPICS datatype of the channel
     * @param count The EPICS item count
     * @param enumLabels labels for the EPICS enumeration state if datatype is
     * ENUM, null otherwise
     */
    public void sendInfo(Session session, String pv, boolean couldConnect, DBRType type,
            Integer count,
            String[] enumLabels) {
        JsonObjectBuilder objBuilder
                = Json.createObjectBuilder().add("type", "info").add(
                        "pv", pv).add("connected", couldConnect);

        if (couldConnect) {
            objBuilder.add("datatype",
                    type.getName()).add("count", count);

            if (enumLabels != null) {
                JsonArrayBuilder arrBuilder = Json.createArrayBuilder();
                for (String label : enumLabels) {
                    arrBuilder.add(label);
                }

                objBuilder.add("enum-labels", arrBuilder);
            }
        }

        JsonObject obj = objBuilder.build();
        String msg = obj.toString();
        send(session, pv, msg);
    }

    /**
     * Notification of PV value change.
     *
     * @param session The client
     * @param pv The PV
     * @param dbr The EPICS DataBaseRecord
     */
    public void sendUpdate(Session session, String pv, DBR dbr) {
        JsonObjectBuilder builder = Json.createObjectBuilder().add("type", "update").add(
                "pv", pv);
        Application.channelManager.addValueToJSON(builder, dbr);
        JsonObject obj = builder.build();
        String msg = obj.toString();
        send(session, pv, msg);
    }

    public void send(Session session, String pv, String msg) {
        if (session.isOpen()) {
            if (Application.USE_QUEUE) {
                //LinkedHashSet updatequeue = (LinkedHashSet) session.getUserProperties().get("updatequeue");
                ConcurrentLinkedQueue<String> writequeue = (ConcurrentLinkedQueue<String>) session.getUserProperties().get("writequeue");

                //System.out.println("Queue Size: " + writequeue.size());
                if (writequeue.size() > 1000) {
                    LOGGER.log(Level.FINEST, "Dropping message: {0}", msg);
                } else {
                    writequeue.offer(msg);
                }
            } else {
                try {
                    synchronized (session) {
                        session.getBasicRemote().sendText(msg);
                    }
                } catch(IllegalStateException e) { // If session closes between time session.isOpen() and sentText(msg) then you'll get this exception.  Not an issue.
                    LOGGER.log(Level.INFO, "Unable to send message", e.getMessage());
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Unable to send message", e);
                }
            }
        } else {
            removeClient(session);
            /*LOGGER.log(Level.FINEST,
                    "Session for PV {0} is closed: {1}",
                    new Object[]{pv, session});*/
        }
    }
}
