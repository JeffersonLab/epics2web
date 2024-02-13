package org.jlab.epics2web.epics;

import com.cosylab.epics.caj.CAJChannel;
import com.cosylab.epics.caj.CAJContext;
import gov.aps.jca.CAException;
import gov.aps.jca.TimeoutException;
import gov.aps.jca.dbr.DBR;
import gov.aps.jca.dbr.DBRType;
import org.jlab.util.LockAcquisitionTimeoutException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;
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
    private final ScheduledExecutorService timeoutExecutor;
    private final ExecutorService callbackExecutor;

    private final ReentrantLock managerLock = new ReentrantLock();

    /**
     * Overloaded server will reject create channel requests after 30 seconds; may also shake deadlock bug in
     * CAJ createChannel.
     */
    private final long ACQUIRE_RESOURCE_TIMEOUT_SECONDS = 30;

    /**
     * After 15 minutes we assume better to leak resource than stay stuck
     */
    private final long CLEANUP_RESOURCE_TIMEOUT_SECONDS = 900;

    /**
     * Create a new ChannelMonitorManager.
     *
     * @param context EPICS channel access context
     * @param timeoutExecutor Thread pool for connection timeout
     * @param callbackExecutor Thread pool for callbacks
     */
    public ChannelManager(CAJContext context, ScheduledExecutorService timeoutExecutor, ExecutorService callbackExecutor) {
        this.context = context;
        this.timeoutExecutor = timeoutExecutor;
        this.callbackExecutor = callbackExecutor;
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
                short value = ((gov.aps.jca.dbr.ENUM) dbr).getEnumValue()[0];
                builder.add("value", value);
            } else if (dbr.isBYTE()) {
                byte[] value = ((gov.aps.jca.dbr.BYTE) dbr).getByteValue();
                int len = value.length;
                if (len > 1) {
                    // epics2web generally doesn't handle arrays,
                    // but for BYTE[] assume that data is really "long string".
                    // Text ends at first '\0' or end of array
                    for (int i=0; i<len; ++i)
                        if (value[i] == 0) {
                            len = i;
                            break;
                        }
                    builder.add("value", new String(value, 0, len, "UTF-8"));
                }
                else
                    builder.add("value", value[0]);
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
                        context.destroyChannel(channels[i], false); // ChannelMonitor.close() also uses this context so don't force
                        //channels[i].destroy(); // This can deadlock
                    }
                }
            }
        }

        return dbrList;
    }

    private DBR doGet(CAJChannel channel, boolean enumLabel) throws CAException {
        DBR dbr;

        try {
            if (enumLabel && channel.getFieldType().isENUM()) {
                dbr = channel.get(DBRType.STRING, 1);
            } else {
                dbr = channel.get();
            }
        } catch(Exception e) { // wrap and add channel name to help with debugging (catch runtime IllegalStateException).
            throw new CAException("Could not get channel " + channel.getName(), e);
        }

        return dbr;
    }

    /**
     * Registers a PV monitor on the supplied PV for the given listener. Note
     * that internally only a single monitor is used for any given PV. PVs for
     * which the given listener is already listening to are skipped (duplicate
     * PVs are ignored). There is no need to call addListener before calling
     * this method.
     *
     * @param listener The PvListener to receive notifications
     * @param pv The PV to monitor
     */
    public void addPv(PvListener listener, String pv) throws InterruptedException, CAException, LockAcquisitionTimeoutException {
        LOGGER.log(Level.FINEST, "addPv: {0} {1}", new Object[] {listener, pv});
        ChannelMonitor monitor = null;
        monitor = monitorMap.get(pv);

        // INTERNAL HOLDING LOCK
        if(managerLock.tryLock(ACQUIRE_RESOURCE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            try {
                if (monitor == null) {
                    //LOGGER.log(Level.FINEST, "Opening ChannelMonitor: {0}", pv);
                    // HERE IS THE HEAVYWEIGHT ACTION: It's an async create channel request, but is still
                    // bottleneck; We're holding a lock while we wait...
                    monitor = new ChannelMonitor(pv, context, timeoutExecutor, callbackExecutor);
                    monitorMap.put(pv, monitor);
                } else {
                    //LOGGER.log(Level.FINEST, "Joining ChannelMonitor: {0}", pv);
                }

                Set<String> clientPvSet = clientMap.get(listener);

                if (clientPvSet == null) {
                    clientPvSet = new HashSet<>();
                }

                clientPvSet.add(pv);

                clientMap.put(listener, clientPvSet);
            } finally {
                managerLock.unlock();
            }
        } else {
            throw new LockAcquisitionTimeoutException("Timeout while acquiring managerLock in addPv");
        }

        // EXTERNAL NO LOCK
        monitor.addListener(listener);
    }

    /**
     * Removes the PV from the given listener.  If the last listener on a given channel the monitor is also removed.
     *
     * @param listener The PvListener
     * @param pv The PV to remove
     */
    public void removePv(PvListener listener, String pv) throws InterruptedException, LockAcquisitionTimeoutException {
        LOGGER.log(Level.FINEST, "removePv: {0} {1}", new Object[] {listener, pv});
        int listenerCount = 0;
        ChannelMonitor monitor = monitorMap.get(pv);

        if (monitor != null) {
            monitor.removeListener(listener);
        }

        // INTERNAL HOLDING LOCK
        if(managerLock.tryLock(CLEANUP_RESOURCE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            if (monitor != null) {
                listenerCount = monitor.getListenerCount();
                if (listenerCount == 0) {
                    monitorMap.remove(pv);
                }
            }

            try {
                Set<String> clientPvSet = clientMap.get(listener);

                if (clientPvSet != null) {
                    clientPvSet.remove(pv);
                }

                clientMap.put(listener, clientPvSet);
            } finally {
                managerLock.unlock();
            }
        } else {
            throw new LockAcquisitionTimeoutException("Timeout while acquiring managerLock in removePv");
        }

        // EXTERNAL NO LOCK
        if (monitor != null && listenerCount == 0) {
            try {
                monitor.close();
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Unable to close monitor", e);
            }
        }
    }

    /**
     * Removes the specified listener and unregisters any PVs the listener was
     * interested in.
     *
     * @param listener The PvListener
     * @return a map of PV names to Exceptions for any PVs that were unable to be removed
     */
    public Map<String, Exception> removeAll(PvListener listener) {
        LOGGER.log(Level.FINEST, "removeAll: {0}", listener);
        Set<String> pvSet = clientMap.remove(listener);

        Map<String, Exception> failed = new HashMap<>();
        if(pvSet != null) {
            for (String pv : pvSet) {
                try {
                    removePv(listener, pv);
                } catch (InterruptedException | LockAcquisitionTimeoutException e) {
                    failed.put(pv, e);
                }
            }
        }

        return failed;
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
