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
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Responsible for monitoring an EPICS channel and notifying registered
 * listeners.
 *
 * @author slominskir
 */
class ChannelMonitor implements Closeable {

    private static final Logger LOGGER = Logger.getLogger(ChannelMonitor.class.getName());

    public static final int PEND_IO_MILLIS = 3000;
    public static final long TIMEOUT_MILLIS = 3000;

    private volatile DBR lastDbr = null;
    private volatile MonitorState state = MonitorState.CONNECTING; // We don't use CAJChannel.getConnectionState() because we want to still be "connecting" during enum label fetch
    private AtomicReference<String[]> enumLabels = new AtomicReference<>(null); // volatile arrays are unsafe due to individual indicies so use AtomicReference instead 

    /*We use a thread-safe set for listeners so that adding/removing/iterating can be done safely*/
    private final Set<PvListener> listeners = new CopyOnWriteArraySet<>();
    private CAJChannel c;
    private final CAJContext context;
    private final ScheduledExecutorService timeoutExecutor;
    private final String pv;

    enum MonitorState {
        CONNECTING, CONNECTED, DISCONNECTED;
    }

    /**
     * Create a new ChannelMonitor for the given EPICS PV using the supplied CA
     * Context.
     *
     * @param pv The PV name
     * @param context The EPICS CA Context
     * @param timeoutExecutor The thread pool to use for connection timeout
     */
    public ChannelMonitor(String pv, CAJContext context, ScheduledExecutorService timeoutExecutor) throws CAException {
        this.pv = pv;
        this.context = context;
        this.timeoutExecutor = timeoutExecutor;

        //LOGGER.log(Level.FINEST, "Creating channel: {0}", pv);
        c = (CAJChannel) context.createChannel(pv, new TimedChannelConnectionListener());

        context.flushIO();
    }

    /**
     * Add a new PvListener.
     *
     * @param listener The PvListener
     */
    public void addListener(PvListener listener) {
        listeners.add(listener);

        boolean connecting = (state == MonitorState.CONNECTING);

        if (!connecting) {
            notifyPvInfo(listener);

            DBR dbr = lastDbr;

            if (dbr != null) {
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
        boolean connected = (state == MonitorState.CONNECTED);

        if (connected) {
            type = c.getFieldType();
            count = c.getElementCount();
        }

        // ABSOLUTELY DO NOT CALL NOTIFY WHILE HOLDING A LOCK
        listener.notifyPvInfo(pv, connected, type, count, enumLabels.get());
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
    private class TimedChannelConnectionListener implements ConnectionListener {

        private final ScheduledFuture future;

        /**
         * Creates a new ChannelConnectionListener.
         */
        public TimedChannelConnectionListener() {
            future = timeoutExecutor.schedule(new Callable<Void>() {
                @Override
                public Void call() throws Exception {
                    boolean connected = (state == MonitorState.CONNECTED);

                    if (!connected) {
                        //LOGGER.log(Level.WARNING, "Unable to connect to channel (timeout)");

                        notifyPvInfoAll();
                    }

                    return null;
                }
            }, TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        }

        /**
         * Handle a connection event.
         *
         * @param ce The ConnectionEvent
         */
        @Override
        public void connectionChanged(ConnectionEvent ce) {
            try {
                future.cancel(false);

                if (ce.isConnected()) {
                    DBRType type = c.getFieldType();

                    if (type == DBRType.ENUM) {
                        handleEnumConnection();
                    } else {
                        handleRegularConnection();
                    }
                } else {
                    //CAJChannel c = (CAJChannel) ce.getSource();
                    //LOGGER.log(Level.WARNING, "Unable to connect to channel: {0}", c.getName());                    

                    state = MonitorState.DISCONNECTED;
                    notifyPvInfoAll();
                }
            } catch (CAException e) {
                LOGGER.log(Level.SEVERE, "Unable to monitor channel", e);
                state = MonitorState.DISCONNECTED;
                notifyPvInfoAll();
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

            state = MonitorState.CONNECTED;
            notifyPvInfoAll();
        }

        /**
         * Setup an enum connection. A connection of an enum-valued PV requires
         * additional metadata - the enum labels.
         *
         * @throws IllegalStateException If unable to initialize
         * @throws CAException If unable to initialize
         */
        private void handleEnumConnection() throws IllegalStateException, CAException {
            c.get(DBRType.LABELS_ENUM, 1, new TimedChannelEnumGetListener());

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
        private class TimedChannelEnumGetListener implements GetListener {

            private final ScheduledFuture future;

            public TimedChannelEnumGetListener() {
                future = timeoutExecutor.schedule(new Callable<Void>() {
                    @Override
                    public Void call() throws Exception {
                        state = MonitorState.DISCONNECTED;

                        notifyPvInfoAll();

                        return null;
                    }
                }, TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            }

            @Override
            public void getCompleted(GetEvent ge) {
                future.cancel(false);
                DBR_LABELS_Enum labelRecord = (DBR_LABELS_Enum) ge.getDBR();
                enumLabels.set(labelRecord.getLabels());

                state = MonitorState.CONNECTED;

                try {
                    registerMonitor();

                    context.flushIO();

                    notifyPvInfoAll();
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

            lastDbr = dbr;

            notifyPvUpdateAll(dbr);
        }
    }
}
