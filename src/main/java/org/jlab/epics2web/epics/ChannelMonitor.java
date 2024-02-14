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
import java.util.Date;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Responsible for monitoring an EPICS channel and notifying registered
 * listeners.
 *
 * @author slominskir
 */
public class ChannelMonitor implements Closeable {

    private static final Logger LOGGER = Logger.getLogger(ChannelMonitor.class.getName());

    public static final long TIMEOUT_MILLIS = 3000;

    private volatile DBR lastDbr = null;

    /**
     * We don't use TIME typed DBR, so we just track 'received' timestamp (which may differ from IOC 'generated' timestamp)
     */
    private volatile Date lastTimestamp = null;

    private final AtomicReference<MonitorState> state = new AtomicReference<>(MonitorState.CONNECTING); // We don't use CAJChannel.getConnectionState() because we want to still be "connecting" during enum label fetch
    private final AtomicReference<String[]> enumLabels = new AtomicReference<>(null); // volatile arrays are unsafe due to individual indicies so use AtomicReference instead 
    private Monitor monitor = null; // Keep track of singleton monitor to avoid creating multiple on reconnect after disconnect

    /*We use a thread-safe set for listeners so that adding/removing/iterating can be done safely*/
    private final Set<PvListener> listeners = new CopyOnWriteArraySet<>();
    private final CAJChannel channel;
    private final CAJContext context;
    private final ScheduledExecutorService timeoutExecutor;
    private final ExecutorService callbackExecutor;
    private final String pv;

    enum MonitorState {
        CONNECTING, CONNECTED, DISCONNECTED;
    }

    /**
     * Create a new ChannelMonitor for the given EPICS PV using the supplied CA
     * Context.
     *
     * NOTE: Updates including metadata info, may be pushed to clients 
     * out-of-order, though this should be rare.  Serializing CAJ callbacks up 
     * until the websocket write queue is costly and unnecessary.  If updates 
     * come in quickly then an out-of-order update will soon be overwritten 
     * anyways.  Worst case is probably metadata info during IOC disconnect and 
     * reconnect, especially for a client connecting to epics2web
     * during a reconnect.  Clients are encouraged to interpret a value 
     * update as meaning state connected in the event it arrives after a 
     * disconnected metadata update.
     * 
     * @param pv The PV name
     * @param context The EPICS CA Context
     * @param timeoutExecutor The thread pool to use for connection timeout
     */
    public ChannelMonitor(String pv, CAJContext context, ScheduledExecutorService timeoutExecutor, ExecutorService callbackExecutor) throws CAException {
        this.pv = pv;
        this.context = context;
        this.timeoutExecutor = timeoutExecutor;
        this.callbackExecutor = callbackExecutor;

        long start = System.currentTimeMillis();
        channel = (CAJChannel) context.createChannel(pv, new TimedChannelConnectionListener());
        context.flushIO();
        long stop = System.currentTimeMillis();
        float elapsedSeconds = (stop - start) / 1000.0f;
        LOGGER.log(Level.FINEST, "Created channel {0} in {1} seconds", new Object[]{pv, elapsedSeconds});
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

    public MonitorState getState() {
        return state.get();
    }

    public String getLastValue() {
        return ChannelManager.getDbrValueAsString(lastDbr);
    }

    public Date getLastTimestamp() {
        return lastTimestamp;
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
                long start = System.currentTimeMillis();
                context.destroyChannel(channel, false); // Don't force because ChannelManager.get() also uses same context!
                long stop = System.currentTimeMillis();
                float elapsedSeconds = (stop - start) / 1000.0f;
                LOGGER.log(Level.FINEST, "Closed Channel {0} in {1} seconds", new Object[]{pv, elapsedSeconds});
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
                        LOGGER.log(Level.FINE, "Unable to connect to channel {0} (timeout)", pv);

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
            // Action sometimes calls back into CA lib, which isn't re-entrant so we use a separate thread
            callbackExecutor.submit(new Runnable(){
                @Override
                public void run() {
                    LOGGER.log(Level.FINEST, "Channel {0} Connection Changed - Connected: {1}", new Object[]{pv, ce.isConnected()});

                    try {
                        future.cancel(false); // only needed for initial connection, on reconnects this will result in "false" return value, which is ignored

                        if (ce.isConnected()) {
                            DBRType type = channel.getFieldType();

                            if (type == DBRType.ENUM) {
                                handleEnumConnection();
                            } else {
                                handleRegularConnectionOrReconnect();
                            }
                        } else {
                            LOGGER.log(Level.FINEST, "Notifying clients of disconnect from channel: {0}", pv);

                            state.set(MonitorState.DISCONNECTED);
                            notifyPvInfoAll(false);
                        }
                    } catch (CAException e) {
                        LOGGER.log(Level.SEVERE, "Unable to monitor channel", e);
                        state.set(MonitorState.DISCONNECTED);
                        notifyPvInfoAll(false);
                    }
                }
            });
        }

        /**
         * Setup a connection or reconnect.
         *
         * @throws IllegalStateException If unable to initialize
         * @throws CAException If unable to initialize
         */
        private void handleRegularConnectionOrReconnect() throws
                IllegalStateException, CAException {
            // Only create monitor on first connect, afterward reconnect uses same old monitor
            synchronized (this) {
                if (monitor == null) {
                    LOGGER.log(Level.FINEST, "Creating {0} Channel Monitor", pv);
                    // We generally don't handle arrays,
                    // except for BYTE[], where we assume a "long string"
                    int count = 1;
                    if (channel.getFieldType().isBYTE() &&
                        channel.getElementCount() > 1)
                        count = channel.getElementCount();
                    monitor = channel.addMonitor(channel.getFieldType(), count, Monitor.VALUE, new ChannelMonitorListener());
                    context.flushIO();
                } else {
                    LOGGER.log(Level.FINEST, "Reusing existing {0} Channel Monitor", pv);
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
            LOGGER.log(Level.FINEST, "Fetching enum labels for {0}", pv);
            channel.get(DBRType.LABELS_ENUM, 1, new TimedChannelEnumGetListener());

            context.flushIO();
        }

        /**
         * A private inner class to respond to an enum label caget.
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
                // Action sometimes calls back into CA lib, which isn't re-entrant so we use a separate thread
                callbackExecutor.submit(new Runnable(){
                    @Override
                    public void run() {
                        future.cancel(false);
                        DBR_LABELS_Enum labelRecord = (DBR_LABELS_Enum) ge.getDBR();
                        enumLabels.set(labelRecord.getLabels());

                        try {
                            handleRegularConnectionOrReconnect();
                        } catch (Exception e) {
                            LOGGER.log(Level.WARNING, "Unable to register monitor after enum label fetch", e);
                        }
                    }
                });
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
            lastTimestamp = new Date();

            // Make sure handlers do not call back into CA lib on this callback thread.
            // We could call in separate thread, but that's costly and then you must
            // then be careful not to pass dbr out-of-order (use lastDbr directly, which could skip intermediate
            // updates and duplicate lastDbr)
            notifyPvUpdateAll(lastDbr);
        }
    }
}
