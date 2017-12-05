package org.jlab.epics2web.epics;

import com.cosylab.epics.caj.CAJChannel;
import com.cosylab.epics.caj.CAJContext;
import gov.aps.jca.CAException;
import gov.aps.jca.TimeoutException;
import gov.aps.jca.dbr.DBR;
import gov.aps.jca.dbr.DBRType;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import javax.json.JsonObjectBuilder;

public class ChannelManager {

    /**
     * Number of seconds to wait for IO operations before a timeout exception
     * occurs.
     */
    public static final double PEND_TIMEOUT_SECONDS = 2.0d;

    private static final Logger LOGGER = Logger.getLogger(ChannelManager.class.getName());

    private final Map<String, ChannelMonitor> monitorMap = new ConcurrentHashMap<>();
    private final Map<PvListener, Set<String>> clientMap = new ConcurrentHashMap<>();

    private volatile CAJContext context;
    private final ScheduledExecutorService executor;

    /**
     * Create a new ChannelMonitorManager.
     *
     * @param context EPICS channel access context
     * @param executor Thread pool for connection timeout
     */
    public ChannelManager(CAJContext context, ScheduledExecutorService executor) {
        this.context = context;
        this.executor = executor;
    }

    public void reset(CAJContext context) {
        try {
            this.context.destroy(); // Destroy old context
        } catch (Exception e) { // IllegalStateException or CAException or whatever
            LOGGER.log(Level.SEVERE, "Unable to destroy context with unresponsive virtual circuit", e);
        }

        synchronized (monitorMap) {
            monitorMap.clear();
            this.context = context; // Assign new context
        }

        Map<PvListener, Set<String>> old;

        synchronized (clientMap) {
            old = new HashMap<>(clientMap);
            for (PvListener listener : old.keySet()) {
                clientMap.put(listener, Collections.emptySet());
            }
        }

        for (PvListener listener : old.keySet()) {
            Set<String> pvs = old.get(listener);
            String listOPvs = pvs.stream().collect(Collectors.joining(" "));
            //addPvs(listener, pvs);
            LOGGER.log(Level.INFO, "Client: {0}, PVs: {1}", new Object[]{listener, listOPvs});
        }
    }

    public void addValueToJSON(JsonObjectBuilder builder, DBR dbr) {
        try {
            if (dbr.isDOUBLE()) {
                double value = ((gov.aps.jca.dbr.DOUBLE) dbr).getDoubleValue()[0];
                if (Double.isFinite(value)) {
                    builder.add("value", value);
                } else if (Double.isNaN(value)) {
                    builder.add("value", "NaN");
                } else {
                    builder.add("value", "Infinity");
                }
            } else if (dbr.isFLOAT()) {
                float value = ((gov.aps.jca.dbr.FLOAT) dbr).getFloatValue()[0];
                if (Float.isFinite(value)) {
                    builder.add("value", value);
                } else if (Float.isNaN(value)) {
                    builder.add("value", "NaN");
                } else {
                    builder.add("value", "Infinity");
                }
            } else if (dbr.isINT()) {
                int value = ((gov.aps.jca.dbr.INT) dbr).getIntValue()[0];
                builder.add("value", value);
            } else if (dbr.isSHORT()) {
                short value = ((gov.aps.jca.dbr.SHORT) dbr).getShortValue()[0];
                builder.add("value", value);
            } else if (dbr.isENUM()) {
                System.out.println("is enum!");
                short value = ((gov.aps.jca.dbr.ENUM) dbr).getEnumValue()[0];
                System.out.println(((gov.aps.jca.dbr.ENUM) dbr).toString());
                builder.add("value", value);
                System.out.println(dbr.getType());
            } else if (dbr.isBYTE()) {
                byte value = ((gov.aps.jca.dbr.BYTE) dbr).getByteValue()[0];
                builder.add("value", value);
            } else {
                String value = ((gov.aps.jca.dbr.STRING) dbr).getStringValue()[0];
                builder.add("value", value);
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Unable to create JSON from value", e);
            builder.add("value", "");
            dbr.printInfo();
        }
    }

    /**
     * Perform a synchronous (blocking) CA-GET request of the given PVs.
     *
     * @param pvs The EPICS CA PV names
     * @param enumLabel true if result should be enum label (ignored if not of
     * type enum); false for numeric value
     * @return The EPICS DataBaseRecord
     * @throws CAException If unable to perform the CA-GET due to IO
     * @throws TimeoutException If unable to perform the CA-GET in a timely
     * fashion
     */
    public List<DBR> get(String[] pvs, boolean enumLabel) throws CAException, TimeoutException {

        // TODO: If we were really clever we could check if a PV is currently being monitored and just return the most recent value.
        List<DBR> dbrList = new ArrayList<>();

        if (pvs != null && pvs.length > 0) {
            CAJChannel[] channels = new CAJChannel[pvs.length];

            try {
                for (int i = 0; i < pvs.length; i++) {
                    channels[i] = (CAJChannel) context.createChannel(pvs[i]);
                }

                context.pendIO(PEND_TIMEOUT_SECONDS);

                for (int i = 0; i < pvs.length; i++) {
                    dbrList.add(doGet(channels[i], enumLabel));
                }

                context.pendIO(PEND_TIMEOUT_SECONDS);
            } finally {
                for (int i = 0; i < pvs.length; i++) {
                    if (channels[i] != null) {
                        channels[i].destroy();
                    }
                }
            }
        }

        return dbrList;
    }

    private DBR doGet(CAJChannel channel, boolean enumLabel) throws CAException {
        DBR dbr;

        if (enumLabel && channel.getFieldType().isENUM()) {
            dbr = channel.get(DBRType.STRING, 1);
        } else {
            dbr = channel.get();
        }

        return dbr;
    }

    /**
     * Registers a PV monitor on the supplied PV for the given listener.
     * Equivalent to calling addPvs with a set of one PV.
     *
     * @param listener The PvListener
     * @param pv The EPICS PV name
     */
    public void addPv(PvListener listener, String pv) {
        HashSet<String> pvSet = new HashSet<>();
        pvSet.add(pv);
        addPvs(listener, pvSet);
    }

    /**
     * Registers PV monitors on the supplied PVs for the given listener. Note
     * that internally only a single monitor is used for any given PV. PVs for
     * which the given listener is already listening to are skipped (duplicate
     * PVs are ignored). There is no need to call addListener before calling
     * this method.
     *
     * @param listener The PvListener to receive notifications
     * @param addPvSet The set of PVs to monitor
     */
    public void addPvs(PvListener listener, Set<String> addPvSet) {
        Set<String> newPvSet = new HashSet<>();

        if (addPvSet != null) {
            // Make sure empty string isn't included as a PV as that is invalid and is ignored
            boolean emptyIncluded = addPvSet.remove("");

            if (emptyIncluded) {
                LOGGER.log(Level.FINEST, "Empty string ignored in add PV request");
            }

            newPvSet.addAll(addPvSet);

            for (String pv : addPvSet) {
                //LOGGER.log(Level.FINEST, "addListener pv: {0}; pv: {1}", new Object[]{session, pv});

                ChannelMonitor monitor = null;
                synchronized (monitorMap) {
                    monitor = monitorMap.get(pv);

                    if (monitor == null) {
                        //LOGGER.log(Level.FINEST, "Opening ChannelMonitor: {0}", pv);
                        try {
                            monitor = new ChannelMonitor(pv, context, executor);
                            monitorMap.put(pv, monitor);
                        } catch (CAException e) {
                            LOGGER.log(Level.WARNING, "Unable to create channel monitor; skipping", e);
                        }
                    } else {
                        //LOGGER.log(Level.FINEST, "Joining ChannelMonitor: {0}", pv);
                    }
                }

                if (monitor != null) {
                    monitor.addListener(listener);
                }
            }
        }

        synchronized (clientMap) {
            Set<String> oldPvSet = clientMap.get(listener);

            if (oldPvSet != null) {
                newPvSet.addAll(oldPvSet);
            }

            clientMap.put(listener, newPvSet);
        }
    }

    /**
     * Removes the supplied PVs from the given listener.
     *
     * @param listener The PvListener
     * @param clearPvSet The PV set to clear
     */
    public void clearPvs(PvListener listener, Set<String> clearPvSet) {
        Set<String> newPvSet;

        synchronized (clientMap) {
            Set<String> oldPvSet = clientMap.get(listener);

            if (oldPvSet != null) {
                newPvSet = new HashSet<>(oldPvSet);
                newPvSet.removeAll(clearPvSet);
            } else {
                newPvSet = new HashSet<>();
            }
            clientMap.put(listener, newPvSet);
        }

        removeFromChannels(listener, clearPvSet);
    }

    /**
     * A convenience method to add a listener without registering any PVs to
     * monitor. This is a rare use-case and is equivalent to calling addPvs with
     * a null set of PVs.
     *
     * Allowing a listener without any PVs registered may be deprecated in the
     * future.
     *
     * @param listener The PvListener
     */
    public void addListener(PvListener listener) {

        synchronized (clientMap) {
            Set<String> pvSet = clientMap.get(listener);

            if (pvSet == null) {
                pvSet = new HashSet<>();
            }

            clientMap.put(listener, pvSet);
        }
    }

    /**
     * Removes a listener from channels and if no listeners remain on a given
     * channel then closes the channel.
     *
     * @param listener The PvListener
     * @param pvList The PV list (and indirectly the channel list)
     */
    private void removeFromChannels(PvListener listener, Set<String> pvSet) {
        if (pvSet != null) { // Some clients don't immediately connect to a pv so have an empty pv list
            for (String pv : pvSet) {
                int listenerCount = 0;

                ChannelMonitor monitor = monitorMap.get(pv);

                if (monitor != null) {
                    monitor.removeListener(listener);
                }

                synchronized (monitorMap) {
                    monitor = monitorMap.get(pv);

                    if (monitor != null) {
                        listenerCount = monitor.getListenerCount();
                        if (listenerCount == 0) {
                            monitorMap.remove(pv);
                        }
                    }
                }

                // We call close without holding a lock
                if (monitor != null && listenerCount == 0) {
                    try {
                        monitor.close();
                    } catch (IOException e) {
                        LOGGER.log(Level.WARNING, "Unable to close monitor", e);
                    }
                }
            }
        }
    }

    /**
     * Removes the specified listener and unregisters any PVs the listener was
     * interested in.
     *
     * @param listener The PvListener
     */
    public void removeListener(PvListener listener) {
        //LOGGER.log(Level.FINEST, "removeListener: {0}", session);
        Set<String> pvSet = clientMap.remove(listener);

        // Don't do this while holding writeLock above since this method could be called by monitorChanged or from websocket close!
        removeFromChannels(listener, pvSet);
    }

    /**
     * Returns a map of PVs to count of listeners for informational purposes.
     *
     * @return The PV to monitor map
     */
    public Map<String, Integer> getMonitorMap() {
        Map<String, Integer> map;
        Map<String, ChannelMonitor> copy = new HashMap<>(monitorMap); // First copy map so that concurrent changes won't bother us
        map = new HashMap<>();
        for (String pv : copy.keySet()) {
            map.put(pv, copy.get(pv).getListenerCount());
        }
        return map;
    }

    /**
     * Returns an unmodifiable map of listeners to their PVs for informational
     * purposes.
     *
     * @return The listener to PVs map
     */
    public Map<PvListener, Set<String>> getClientMap() {
        Map<PvListener, Set<String>> map;
        map = Collections.unmodifiableMap(clientMap);
        return map;
    }
}
