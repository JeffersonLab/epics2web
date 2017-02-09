package org.jlab.epics2web.epics;

import com.cosylab.epics.caj.CAJContext;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ChannelMonitorManager {

    private static final Logger LOGGER = Logger.getLogger(ChannelMonitorManager.class.getName());

    private final ReadWriteLock rwLock = new ReentrantReadWriteLock();
    private final Lock readLock = rwLock.readLock();
    private final Lock writeLock = rwLock.writeLock();

    private final Map<String, ChannelMonitor> monitorMap = new HashMap<>();
    private final Map<PvListener, Set<String>> clientMap = new HashMap<>();

    private final CAJContext context;
    private final ScheduledExecutorService executor;

    /**
     * Create a new ChannelMonitorManager.
     *
     * @param context EPICS channel access context
     * @param executor Thread pool for connection timeout
     */
    public ChannelMonitorManager(CAJContext context, ScheduledExecutorService executor) {
        this.context = context;
        this.executor = executor;
    }

    /**
     * Registers a PV monitor on the supplied PV for the given listener. Equivalent to calling
     * addPvs with a set of one PV.
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
     * Registers PV monitors on the supplied PVs for the given listener. Note that internally only a
     * single monitor is used for any given PV. PVs for which the given listener is already
     * listening to are skipped (duplicate PVs are ignored). There is no need to call addListener
     * before calling this method.
     *
     * @param listener The PvListener to receive notifications
     * @param addPvSet The set of PVs to monitor
     */
    public void addPvs(PvListener listener, Set<String> addPvSet) {
        writeLock.lock();
        try {
            Set<String> newPvSet = new HashSet<>();

            if (addPvSet != null) {
                newPvSet.addAll(addPvSet);

                for (String pv : addPvSet) {
                    //LOGGER.log(Level.FINEST, "addListener pv: {0}; pv: {1}", new Object[]{session, pv});
                    ChannelMonitor monitor = monitorMap.get(pv);

                    if (monitor == null) {
                        //LOGGER.log(Level.FINEST, "Opening ChannelMonitor: {0}", pv);
                        monitor = new ChannelMonitor(pv, context, executor);
                        monitorMap.put(pv, monitor);
                    } else {
                        //LOGGER.log(Level.FINEST, "Joining ChannelMonitor: {0}", pv);
                    }

                    monitor.addListener(listener);
                }
            }

            Set<String> oldPvSet = clientMap.get(listener);

            if (oldPvSet != null) {
                newPvSet.addAll(oldPvSet);
            }

            clientMap.put(listener, newPvSet);
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Removes the supplied PVs from the given listener.
     *
     * @param listener The PvListener
     * @param clearPvSet The PV set to clear
     */
    public void clearPvs(PvListener listener, Set<String> clearPvSet) {
        writeLock.lock();
        try {
            Set<String> newPvSet;
            Set<String> oldPvSet = clientMap.get(listener);

            if (oldPvSet != null) {
                newPvSet = new HashSet<>(oldPvSet);
                newPvSet.removeAll(clearPvSet);
            } else {
                newPvSet = new HashSet<>();
            }

            removeFromChannels(listener, clearPvSet);
            clientMap.put(listener, newPvSet);
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * A convenience method to add a listener without registering any PVs to monitor. This is a rare
     * use-case and is equivalent to calling addPvs with a null set of PVs.
     *
     * Allowing a listener without any PVs registered may be deprecated in the future.
     *
     * @param listener The PvListener
     */
    public void addListener(PvListener listener) {
        writeLock.lock();
        try {
            Set<String> pvSet = clientMap.get(listener);

            clientMap.put(listener, pvSet);
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Removes a listener from channels and if no listeners remain on a given channel then closes
     * the channel.
     *
     * @param listener The PvListener
     * @param pvList The PV list (and indirectly the channel list)
     */
    private void removeFromChannels(PvListener listener, Set<String> pvSet) {
        if (pvSet != null) { // Some clients don't immediately connect to a pv so have an empty pv list
            for (String pv : pvSet) {
                ChannelMonitor monitor = monitorMap.get(pv);

                if (monitor != null) {
                    monitor.removeListener(listener);

                    if (monitor.getListenerCount() == 0) {
                        //LOGGER.log(Level.FINEST, "Closing ChannelMonitor: {0}", pv);
                        try {
                            monitor.close();
                        } catch (IOException e) {
                            LOGGER.log(Level.WARNING, "Unable to close monitor", e);
                        }
                        monitorMap.remove(pv);
                    }
                }
            }
        }
    }

    /**
     * Removes the specified listener and unregisters any PVs the listener was interested in.
     *
     * @param listener The PvListener
     */
    public void removeListener(PvListener listener) {
        //LOGGER.log(Level.FINEST, "removeListener: {0}", session);
        Set<String> pvSet;
        writeLock.lock();
        try {
            pvSet = clientMap.get(listener);

            clientMap.remove(listener);
        } finally {
            writeLock.unlock();
        }
        
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
        readLock.lock();
        try {
            map = new HashMap<>();
            for (String pv : monitorMap.keySet()) {
                map.put(pv, monitorMap.get(pv).getListenerCount());
            }
        } finally {
            readLock.unlock();
        }
        return map;
    }

    /**
     * Returns an unmodifiable map of listeners to their PVs for informational purposes.
     *
     * @return The listener to PVs map
     */
    public Map<PvListener, Set<String>> getClientMap() {
        Map<PvListener, Set<String>> map;
        readLock.lock();
        try {
            map = Collections.unmodifiableMap(clientMap);
        } finally {
            readLock.unlock();
        }
        return map;
    }
}
