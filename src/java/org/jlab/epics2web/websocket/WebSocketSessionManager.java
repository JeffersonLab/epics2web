package org.jlab.epics2web.websocket;

import gov.aps.jca.dbr.DBR;
import gov.aps.jca.dbr.DBRType;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
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

    private final ReadWriteLock rwLock = new ReentrantReadWriteLock();
    private final Lock readLock = rwLock.readLock();
    private final Lock writeLock = rwLock.writeLock();

    private final Map<Session, WebSocketSessionMonitor> listenerMap = new HashMap<>();

    /**
     * Send a pong reply. This is generally done in response to a client ping.
     *
     * @param session The web socket session
     * @throws IOException If unable to send the message.
     */
    public void sendPong(Session session) throws IOException {
        JsonObjectBuilder objBuilder = Json.createObjectBuilder().add("type", "pong");
        JsonObject obj = objBuilder.build();

        if (session.isOpen()) {
            synchronized (session) {
                session.getBasicRemote().sendText(obj.toString());
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
            writeLock.lock();
            try {
                listenerMap.remove(session);
            } finally {
                writeLock.unlock();
            }

            // Don't do this while holding writeLock above since this method could be called by monitorChanged or from websocket close!
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

        readLock.lock();
        try {
            for (Session session : listenerMap.keySet()) {
                WebSocketSessionMonitor listener = listenerMap.get(session);
                Set<String> pvSet = pvMap.get(listener);
                clientMap.put(session, pvSet);
            }
        } finally {
            readLock.unlock();
        }

        return clientMap;
    }

    private WebSocketSessionMonitor getListener(Session session) {
        WebSocketSessionMonitor listener;
        writeLock.lock();
        try {
            listener = listenerMap.get(session);

            if (listener == null) {
                listener = new WebSocketSessionMonitor(session, this);
                listenerMap.put(session, listener);
            }
        } finally {
            writeLock.unlock();
        }

        return listener;
    }

    /**
     * Notification of PV metadata sent after registering a PV with a ChannelMonitor.
     *
     * @param session The client
     * @param pv The PV that was registered
     * @param couldConnect true if the channel connected, false otherwise
     * @param type The EPICS datatype of the channel
     * @param count The EPICS item count
     * @param enumLabels labels for the EPICS enumeration state if datatype is ENUM, null otherwise
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
        if (session.isOpen()) {
            try {
                synchronized (session) {
                    session.getBasicRemote().sendText(obj.toString());
                }
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Unable to send message", e);
            }
        } else {
            removeClient(session);
            LOGGER.log(Level.WARNING,
                    "Session for PV {0} is closed: {0}",
                    new Object[]{pv, session});
        }
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

        if (dbr.isDOUBLE()) {
            double value = ((gov.aps.jca.dbr.DOUBLE) dbr).getDoubleValue()[0];
            builder.add("value", value);
        } else if (dbr.isFLOAT()) {
            float value = ((gov.aps.jca.dbr.FLOAT) dbr).getFloatValue()[0];
            builder.add("value", value);
        } else if (dbr.isINT()) {
            int value = ((gov.aps.jca.dbr.INT) dbr).getIntValue()[0];
            builder.add("value", value);
        } else if (dbr.isSHORT()) {
            short value = ((gov.aps.jca.dbr.SHORT) dbr).getShortValue()[0];
            builder.add("value", value);
        } else if (dbr.isENUM()) {
            short value = ((gov.aps.jca.dbr.ENUM) dbr).getEnumValue()[0];
            builder.add("value", value);
        } else if (dbr.isBYTE()) {
            byte value = ((gov.aps.jca.dbr.BYTE) dbr).getByteValue()[0];
            builder.add("value", value);
        } else {
            String value = ((gov.aps.jca.dbr.STRING) dbr).getStringValue()[0];
            builder.add("value", value);
        }

        JsonObject obj = builder.build();
        if (session.isOpen()) {
            //LOGGER.log(Level.FINEST, "sending message: {0}", obj.toString());  
            try {
                synchronized (session) {
                    session.getBasicRemote().sendText(obj.toString());
                }
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Unable to send message", e);
            }
        } else {
            removeClient(session);
            LOGGER.log(Level.WARNING,
                    "Session for PV {0} is closed: {0}",
                    new Object[]{pv, session});
        }
    }
}
