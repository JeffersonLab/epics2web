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
    private final AtomicReference<MonitorState> state = new AtomicReference<>(MonitorState.CONNECTING); // We don't use CAJChannel.getConnectionState() because we want to still be "connecting" during enum label fetch; also, we might be "disconnected" when a virtualcircuitexception occurs
    private final AtomicReference<String[]> enumLabels = new AtomicReference<>(null); // volatile arrays are unsafe due to individual indicies so use AtomicReference instead 
    private Monitor monitor = null; // Keep track of singleton monitor to avoid creating multiple on reconnect after disconnect

    /*We use a thread-safe set for listeners so that adding/removing/iterating can be done safely*/
    private final Set<PvListener> listeners = new CopyOnWriteArraySet<>();
    private final CAJChannel channel;
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
        channel = (CAJChannel) context.createChannel(pv, new TimedChannelConnectionListener());

        context.flushIO();
    }

    /**
     * Add a new PvListener.
     *
     * @param listener The PvListener
     */
    public void addListener(PvListener listener) {
        listeners.add(listener);

        switch (state.get()) {
            case CONNECTED:
                notifyPvInfo(listener, true);

                DBR dbr = lastDbr;

                if (dbr != null) {
                    notifyPvUpdate(listener, dbr);
                }
                break;
            case DISCONNECTED:
                notifyPvInfo(listener, false);
                break;
            default: // CONNECTING
                // Wait for timer or connected callback
        }
        
        // NOTE: if connectionChanged occurs simultaneously then client may receive messages out-of-order such as info showing disconnected then connected, when should be connected, then disconnected.
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
        if (channel != null) {
            try {
                // channel.destroy(); // method is unsafe (can deadlock)
                // so use context method instead                
                context.destroyChannel(channel, true);
            } catch (CAException e) {
                throw new IOException("Unable to close channel", e);
            }
        }
    }

    /**
     * Notify all listeners of the channel info metadata.
     */
    private void notifyPvInfoAll(boolean connected) {
        for (PvListener l : listeners) {
            notifyPvInfo(l, connected);
        }
    }

    /**
     * Notify a given listener of the channel info metadata.
     *
     * @param listener The PvListener
     */
    private void notifyPvInfo(PvListener listener, boolean connected) {
        DBRType type = null;
        Integer count = null;

        if (connected) {
            type = channel.getFieldType();
            count = channel.getElementCount();
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
                    boolean connected = (state.get() == MonitorState.CONNECTED);

                    if (!connected) {
                        //LOGGER.log(Level.WARNING, "Unable to connect to channel (timeout)");

                        notifyPvInfoAll(false);
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
            //LOGGER.log(Level.FINEST, "Connection Changed - Connected: {0}", ce.isConnected());

            try {
                future.cancel(false);

                if (ce.isConnected()) {
                    DBRType type = channel.getFieldType();

                    if (type == DBRType.ENUM) {
                        handleEnumConnection();
                    } else {
                        handleRegularConnectionOrReconnect();
                    }
                } else {
                    //CAJChannel c = (CAJChannel) ce.getSource();
                    //LOGGER.log(Level.WARNING, "Unable to connect to channel: {0}", c.getName());                    

                    state.set(MonitorState.DISCONNECTED);
                    notifyPvInfoAll(false);
                }
            } catch (CAException e) {
                LOGGER.log(Level.SEVERE, "Unable to monitor channel", e);
                state.set(MonitorState.DISCONNECTED);
                notifyPvInfoAll(false);
            }
        }

        /**
         * Setup a connection or reconnect.
         *
         * @throws IllegalStateException If unable to initialize
         * @throws CAException If unable to initialize
         */
        private void handleRegularConnectionOrReconnect() throws
                IllegalStateException, CAException {
            // Only create monitor on first connect, afterwards reconnect uses same old monitor
            synchronized (this) {
                if (monitor == null) {
                    monitor = channel.addMonitor(channel.getFieldType(), 1, Monitor.VALUE, new ChannelMonitorListener());
                    context.flushIO();
                }
            }

            state.set(MonitorState.CONNECTED);
            notifyPvInfoAll(true);
        }

        /**
         * Setup an enum connection. A connection of an enum-valued PV requires
         * additional metadata - the enum labels.
         *
         * @throws IllegalStateException If unable to initialize
         * @throws CAException If unable to initialize
         */
        private void handleEnumConnection() throws IllegalStateException, CAException {
            channel.get(DBRType.LABELS_ENUM, 1, new TimedChannelEnumGetListener());

            context.flushIO();
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
                        state.set(MonitorState.DISCONNECTED);

                        notifyPvInfoAll(false);

                        return null;
                    }
                }, TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            }

            @Override
            public void getCompleted(GetEvent ge) {
                future.cancel(false);
                DBR_LABELS_Enum labelRecord = (DBR_LABELS_Enum) ge.getDBR();
                enumLabels.set(labelRecord.getLabels());

                try {
                    handleRegularConnectionOrReconnect();
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
            DBR dbr = me.getDBR();

            lastDbr = dbr;

            notifyPvUpdateAll(dbr);
        }
    }
}
