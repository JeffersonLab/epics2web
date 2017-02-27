package org.jlab.epics2web.epics;

import com.cosylab.epics.caj.CAJChannel;
import com.cosylab.epics.caj.CAJContext;
import gov.aps.jca.CAException;
import gov.aps.jca.Monitor;
import gov.aps.jca.dbr.DBR;
import gov.aps.jca.dbr.DBRType;
import gov.aps.jca.dbr.DBR_LABELS_Enum;
import gov.aps.jca.event.ConnectionEvent;
import gov.aps.jca.event.ConnectionListener;
import gov.aps.jca.event.GetEvent;
import gov.aps.jca.event.GetListener;
import gov.aps.jca.event.MonitorEvent;
import gov.aps.jca.event.MonitorListener;
import java.io.Closeable;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Responsible for monitoring an EPICS channel and notifying registered listeners.
 *
 * @author ryans
 */
class ChannelMonitor implements Closeable {

    private static final Logger LOGGER = Logger.getLogger(ChannelMonitor.class.getName());

    public static final int PEND_IO_MILLIS = 3000;

    /*This LOCK provides thread safety to lastDBR, initialized, and couldConnect variables*/
    private final ReadWriteLock rwLock = new ReentrantReadWriteLock();
    private final Lock readLock = rwLock.readLock();
    private final Lock writeLock = rwLock.writeLock();

    /*We use a thread-safe set for listeners so that adding/removing/iterating can be done safely*/
    private final Set<PvListener> listeners = new CopyOnWriteArraySet<>();
    private CAJChannel c;
    private final CAJContext context;
    private final ScheduledExecutorService executor;
    private final String pv;
    private DBR lastDbr = null;
    private String[] enumLabels;
    private boolean initialized = false;
    private boolean disconnectedPv = false; // Set to true if connection attempt already performed, and it failed (timeout reached)
    private boolean couldConnect = false; // Set to true if connected succesfully before timeout

    /**
     * Create a new ChannelMonitor for the given EPICS PV using the supplied CA Context.
     *
     * @param pv The PV name
     * @param context The EPICS CA Context
     * @param executor The thread pool to use for connection timeout
     */
    public ChannelMonitor(String pv, CAJContext context, ScheduledExecutorService executor) {
        this.pv = pv;
        this.context = context;
        this.executor = executor;

        try {
            //LOGGER.log(Level.FINEST, "Creating channel: {0}", pv);
            c = (CAJChannel) context.createChannel(pv, new ChannelConnectionListener());

            context.flushIO();

        } catch (CAException e) {
            LOGGER.log(Level.SEVERE, "Unable to obtain channel access context", e);
        }
    }

    /**
     * Add a new PvListener.
     *
     * @param listener The PvListener
     */
    public void addListener(PvListener listener) {

        DBR dbr;
        boolean init;
        boolean disconnected;

        listeners.add(listener);

        readLock.lock();
        try {
            dbr = lastDbr;
            init = initialized;
            disconnected = disconnectedPv;
        } finally {
            readLock.unlock();
        }

        if (init || disconnected) {
            notifyPvInfo(listener);
            
            if(dbr != null) {
                notifyPvUpdate(listener, dbr);
            }
        }
    }

    /**
     * Remove the supplied PvListener.
     *
     * @param listener The PvListener
     */
    public void removeListener(PvListener listener) {
        listeners.remove(listener);
    }

    /**
     * Return the number of PvListeners.
     *
     * @return The number of PvListeners
     */
    public int getListenerCount() {
        return listeners.size();
    }

    /**
     * Close the ChannelMonitor.
     *
     * @throws IOException If unable to close
     */
    @Override
    public void close() throws IOException {
        //LOGGER.log(Level.FINEST, "close");
        if (c != null) {
            try {
                c.destroy();
            } catch (CAException e) {
                throw new IOException("Unable to close channel", e);
            }
        }
    }

    /**
     * Notify all listeners of the channel info metadata.
     */
    private void notifyPvInfoAll() {
        for (PvListener l : listeners) {
            notifyPvInfo(l);
        }
    }

    /**
     * Notify a given listener of the channel info metadata.
     *
     * @param listener The PvListener
     */
    private void notifyPvInfo(PvListener listener) {
        DBRType type = null;
        Integer count = null;

        // THESE VALUES DO NOT CHANGE AFTER MONITOR INIT, SO WE DON'T HAVE TO SYNCHRONIZE
        if (couldConnect) {
            type = c.getFieldType();
            count = c.getElementCount();
        }

        // ABSOLUTELY DO NOT CALL NOTIFY WHILE HOLDING A LOCK
        listener.notifyPvInfo(pv, couldConnect, type, count, enumLabels);
    }

    /**
     * Notify all listeners of a channel value update.
     */
    private void notifyPvUpdateAll(DBR dbr) {
        for (PvListener s : listeners) {
            notifyPvUpdate(s, dbr);
        }
    }

    /**
     * Notify a given listener of a channel value update.
     *
     * @param listener The PvListener
     */
    private void notifyPvUpdate(PvListener listener, DBR dbr) {
        // ABSOLUTELY DO NOT CALL NOTIFY WHILE HOLDING A LOCK        
        listener.notifyPvUpdate(pv, dbr);
    }

    /**
     * Private inner helper class to respond to connection status changes.
     */
    private class ChannelConnectionListener implements ConnectionListener {

        private static final long CONNECTION_TIMEOUT_MILLIS = 3000;
        private final ScheduledFuture future;

        /**
         * Creates a new ChannelConnectionListener.
         */
        public ChannelConnectionListener() {
            future = executor.schedule(new Callable<Void>() {
                @Override
                public Void call() throws Exception {
                    boolean connected;

                    writeLock.lock();
                    try {
                        connected = couldConnect;
                        disconnectedPv = !connected;
                    } finally {
                        writeLock.unlock();
                    }

                    if (!connected) {
                        //LOGGER.log(Level.WARNING, "Unable to connect to channel (timeout)");

                        notifyPvInfoAll();
                    }

                    return null;
                }
            }, CONNECTION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        }

        /**
         * Handle a connection event.
         *
         * @param ce The ConnectionEvent
         */
        @Override
        public void connectionChanged(ConnectionEvent ce) {
            //LOGGER.log(Level.FINEST, "Connected");
            try {
                //CAJChannel c2 = (CAJChannel) ce.getSource();

                if (ce.isConnected()) {
                    writeLock.lock();
                    try {
                        couldConnect = true;
                    } finally {
                        writeLock.unlock();
                    }
                    future.cancel(false);

                    DBRType type = c.getFieldType();

                    if (type == DBRType.ENUM) {
                        handleEnumConnection();
                    } else {
                        handleRegularConnection();
                    }
                } else {
                    //LOGGER.log(Level.WARNING, "Unable to connect to channel (connection changed)");
                }
            } catch (CAException e) {
                LOGGER.log(Level.SEVERE, "Unable to monitor channel", e);
            }
        }

        /**
         * Setup a regular (non-enum) connection.
         *
         * @throws IllegalStateException If unable to initialize
         * @throws CAException If unable to initialize
         */
        private void handleRegularConnection() throws
                IllegalStateException, CAException {
            registerMonitor();

            context.flushIO();
        }

        /**
         * Setup an enum connection. A connection of an enum-valued PV requires additional metadata
         * - the enum labels.
         *
         * @throws IllegalStateException If unable to initialize
         * @throws CAException If unable to initialize
         */
        private void handleEnumConnection() throws IllegalStateException, CAException {
            c.get(DBRType.LABELS_ENUM, 1, new ChannelEnumGetListener());

            context.flushIO();
        }

        /**
         * Register the monitor in the low-level EPICS lib plumbing.
         *
         * @throws IllegalStateException If unable to register
         * @throws CAException If unable to register
         */
        private void registerMonitor() throws IllegalStateException, CAException {
            c.addMonitor(c.getFieldType(), 1, Monitor.VALUE, new ChannelMonitorListener());
        }

        /**
         * A private inner inner class to respond to an enum label caget.
         */
        private class ChannelEnumGetListener implements GetListener {

            @Override
            public void getCompleted(GetEvent ge) {
                DBR_LABELS_Enum labelRecord = (DBR_LABELS_Enum) ge.getDBR();
                enumLabels = labelRecord.getLabels();

                try {
                    registerMonitor();

                    context.flushIO();
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Unable to register monitor after enum label fetch", e);
                }
            }
        }
    }

    /**
     * Private inner class to handle monitor callbacks.
     */
    private class ChannelMonitorListener implements MonitorListener {

        /**
         * Handles a monitor event.
         *
         * @param me The MonitorEvent
         */
        @Override
        public void monitorChanged(MonitorEvent me) {
            //LOGGER.log(Level.FINEST, "Monitor Update");
            DBR dbr = me.getDBR();
            boolean notifyInfo = false;
            writeLock.lock();
            try {
                lastDbr = dbr;

                if (!initialized) {
                    initialized = true;
                    notifyInfo = true;
                }
            } finally {
                writeLock.unlock();
            }

            if (notifyInfo) {
                notifyPvInfoAll();
            }

            notifyPvUpdateAll(dbr);
        }
    }
}
