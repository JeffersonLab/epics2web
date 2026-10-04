package org.jlab.epics2web.epics;

import com.cosylab.epics.caj.CAJChannel;
import com.cosylab.epics.caj.CAJContext;
import gov.aps.jca.CAException;
import gov.aps.jca.Channel;
import gov.aps.jca.TimeoutException;
import gov.aps.jca.dbr.DBR;
import gov.aps.jca.dbr.DBRType;
import gov.aps.jca.event.ConnectionEvent;
import gov.aps.jca.event.ConnectionListener;
import gov.aps.jca.event.GetListener;
import jakarta.json.JsonObjectBuilder;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ChannelManager {

  /** Number of seconds to wait for IO operations before a timeout exception occurs. */
  public static final double PEND_TIMEOUT_SECONDS = 2.0d;

  private static final Logger LOGGER = Logger.getLogger(ChannelManager.class.getName());

  /** Must be a ConcurrentHashMap: addPv and removePv rely on its atomic per-key compute. */
  private final ConcurrentHashMap<String, ChannelMonitor> monitorMap = new ConcurrentHashMap<>();

  private final Map<PvListener, Set<String>> clientMap = new ConcurrentHashMap<>();

  private volatile CAJContext context;
  private final ScheduledExecutorService timeoutExecutor;
  private final ExecutorService callbackExecutor;

  /**
   * Create a new ChannelMonitorManager.
   *
   * @param context EPICS channel access context
   * @param timeoutExecutor Thread pool for connection timeout
   * @param callbackExecutor Thread pool for callbacks
   */
  public ChannelManager(
      CAJContext context,
      ScheduledExecutorService timeoutExecutor,
      ExecutorService callbackExecutor) {
    this.context = context;
    this.timeoutExecutor = timeoutExecutor;
    this.callbackExecutor = callbackExecutor;
  }

  public static String getDbrValueAsString(DBR dbr) {
    String strValue = null;
    if (dbr == null) {
      strValue = "";
    } else if (dbr.isDOUBLE()) {
      double value = ((gov.aps.jca.dbr.DOUBLE) dbr).getDoubleValue()[0];
      strValue = String.valueOf(value); // NaN, Infinity or -Infinity if not finite
    } else if (dbr.isFLOAT()) {
      float value = ((gov.aps.jca.dbr.FLOAT) dbr).getFloatValue()[0];
      strValue = String.valueOf(value); // NaN, Infinity or -Infinity if not finite
    } else if (dbr.isINT()) {
      int value = ((gov.aps.jca.dbr.INT) dbr).getIntValue()[0];
      strValue = String.valueOf(value);
    } else if (dbr.isSHORT()) {
      short value = ((gov.aps.jca.dbr.SHORT) dbr).getShortValue()[0];
      strValue = String.valueOf(value);
    } else if (dbr.isENUM()) {
      short value = ((gov.aps.jca.dbr.ENUM) dbr).getEnumValue()[0];
      strValue = String.valueOf(value);
    } else if (dbr.isBYTE()) {
      byte[] value = ((gov.aps.jca.dbr.BYTE) dbr).getByteValue();
      int len = value.length;
      if (len > 1) {
        // epics2web generally doesn't handle arrays,
        // but for BYTE[] assume that data is really "long string".
        // Text ends at first '\0' or end of array
        for (int i = 0; i < len; ++i)
          if (value[i] == 0) {
            len = i;
            break;
          }
        try {
          strValue = new String(value, 0, len, "UTF-8");
        } catch (UnsupportedEncodingException e) {
          throw new RuntimeException("JVM doesn't support UTF-8!");
        }
      } else strValue = String.valueOf(value[0]);
    } else {
      String value = ((gov.aps.jca.dbr.STRING) dbr).getStringValue()[0];
      strValue = value;
    }

    return strValue;
  }

  public void addValueToJSON(JsonObjectBuilder builder, DBR dbr) {
    try {
      if (dbr == null) {
        builder.addNull("value"); // null happens on restart?
      } else if (dbr.isDOUBLE()) {
        double value = ((gov.aps.jca.dbr.DOUBLE) dbr).getDoubleValue()[0];
        if (Double.isFinite(value)) {
          builder.add("value", value);
        } else {
          builder.add("value", String.valueOf(value)); // NaN, Infinity or -Infinity
        }
      } else if (dbr.isFLOAT()) {
        float value = ((gov.aps.jca.dbr.FLOAT) dbr).getFloatValue()[0];
        if (Float.isFinite(value)) {
          builder.add("value", value);
        } else {
          builder.add("value", String.valueOf(value)); // NaN, Infinity or -Infinity
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
          for (int i = 0; i < len; ++i)
            if (value[i] == 0) {
              len = i;
              break;
            }
          builder.add("value", new String(value, 0, len, "UTF-8"));
        } else builder.add("value", value[0]);
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
   * <p>Each PV's channel is created and destroyed inside monitorMap.compute, like a monitor's, so
   * it can't race a monitor or another get of the same PV: CAJ shares channels by name, and a
   * channel created while the same-named channel is being destroyed is handed back closed. The
   * request waits only for its own channels, not for all pending IO in the shared context, so a PV
   * that doesn't connect fails only the requests that ask for it.
   *
   * @param pvs The EPICS CA PV names
   * @param enumLabel true if result should be enum label (ignored if not of type enum); false for
   *     numeric value
   * @return The EPICS DataBaseRecord
   * @throws CAException If unable to perform the CA-GET due to IO
   * @throws TimeoutException If unable to perform the CA-GET in a timely fashion
   */
  public List<DBR> get(String[] pvs, boolean enumLabel) throws CAException, TimeoutException {

    // TODO: If we were really clever we could check if a PV is currently being monitored and just
    // return the most recent value.
    List<DBR> dbrList = new ArrayList<>();

    if (pvs != null && pvs.length > 0) {
      CAJChannel[] channels = new CAJChannel[pvs.length];
      ConnectionLatch[] connections = new ConnectionLatch[pvs.length];

      try {
        for (int i = 0; i < pvs.length; i++) {
          connections[i] = new ConnectionLatch();
          channels[i] = createChannel(pvs[i], connections[i]);
        }
        context.flushIO();

        long deadline = deadline();
        for (int i = 0; i < pvs.length; i++) {
          if (!connections[i].await(channels[i], deadline)) {
            throw new TimeoutException(
                "Channel " + pvs[i] + " didn't connect within " + PEND_TIMEOUT_SECONDS + " s");
          }
        }

        List<CompletableFuture<DBR>> results = new ArrayList<>();
        for (CAJChannel channel : channels) {
          results.add(requestGet(channel, enumLabel));
        }
        context.flushIO();

        deadline = deadline();
        for (int i = 0; i < pvs.length; i++) {
          dbrList.add(awaitGet(pvs[i], results.get(i), deadline));
        }
      } finally {
        for (int i = 0; i < pvs.length; i++) {
          if (channels[i] != null) {
            destroyChannel(pvs[i], channels[i], connections[i]);
          }
        }
      }
    }

    return dbrList;
  }

  private static long deadline() {
    return System.nanoTime() + (long) (PEND_TIMEOUT_SECONDS * 1_000_000_000L);
  }

  private CAJChannel createChannel(String pv, ConnectionListener listener) throws CAException {
    CAJChannel[] channel = new CAJChannel[1];
    try {
      monitorMap.compute(
          pv,
          (k, monitor) -> {
            try {
              channel[0] = (CAJChannel) context.createChannel(pv, listener);
            } catch (CAException e) {
              throw new UncheckedCAException(e);
            }
            return monitor;
          });
    } catch (UncheckedCAException e) {
      throw e.getCause();
    }
    return channel[0];
  }

  private void destroyChannel(String pv, CAJChannel channel, ConnectionListener listener) {
    try {
      channel.removeConnectionListener(listener); // The channel may be shared with a monitor
    } catch (CAException | IllegalStateException e) {
      LOGGER.log(Level.FINE, "Unable to remove connection listener from " + pv, e);
    }

    monitorMap.compute(
        pv,
        (k, monitor) -> {
          try {
            // Don't force: a monitor may share this channel
            context.destroyChannel(channel, false);
          } catch (CAException | IllegalStateException e) {
            LOGGER.log(Level.WARNING, "Unable to destroy channel " + pv, e);
          }
          return monitor;
        });
  }

  private CompletableFuture<DBR> requestGet(CAJChannel channel, boolean enumLabel)
      throws CAException {
    CompletableFuture<DBR> result = new CompletableFuture<>();
    GetListener listener =
        event -> {
          if (event.getStatus().isSuccessful()) {
            result.complete(event.getDBR());
          } else {
            result.completeExceptionally(
                new CAException(
                    "Could not get channel " + channel.getName() + ": " + event.getStatus()));
          }
        };

    try {
      if (enumLabel && channel.getFieldType().isENUM()) {
        channel.get(DBRType.STRING, 1, listener);
      } else {
        channel.get(channel.getFieldType(), channel.getElementCount(), listener);
      }
    } catch (IllegalStateException e) { // wrap and add channel name to help with debugging
      throw new CAException("Could not get channel " + channel.getName(), e);
    }

    return result;
  }

  private static DBR awaitGet(String pv, CompletableFuture<DBR> result, long deadline)
      throws CAException, TimeoutException {
    try {
      return result.get(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
    } catch (java.util.concurrent.TimeoutException e) {
      throw new TimeoutException(
          "Channel " + pv + " didn't answer within " + PEND_TIMEOUT_SECONDS + " s");
    } catch (ExecutionException e) {
      if (e.getCause() instanceof CAException cause) {
        throw cause;
      }
      throw new CAException("Could not get channel " + pv, e.getCause());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new TimeoutException("Interrupted while getting " + pv, e);
    }
  }

  /** Lets a get wait for its own channel to connect. */
  private static class ConnectionLatch implements ConnectionListener {
    private final CountDownLatch connected = new CountDownLatch(1);

    @Override
    public void connectionChanged(ConnectionEvent event) {
      if (event.isConnected()) {
        connected.countDown();
      }
    }

    boolean await(Channel channel, long deadline) throws TimeoutException {
      // A channel shared with a monitor may be connected already, with no event to come
      if (channel.getConnectionState() == Channel.ConnectionState.CONNECTED) {
        return true;
      }
      try {
        return connected.await(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new TimeoutException("Interrupted while connecting to " + channel.getName(), e);
      }
    }
  }

  /**
   * Registers a PV monitor on the supplied PV for the given listener. Note that internally only a
   * single monitor is used for any given PV. PVs for which the given listener is already listening
   * to are skipped (duplicate PVs are ignored). There is no need to call addListener before calling
   * this method.
   *
   * <p>Creating a monitor, joining it, leaving it, and closing it are all done atomically per PV
   * inside monitorMap.compute so that concurrent add and remove of the same PV can't create
   * duplicate monitors or attach a listener to a monitor being closed. Channel create and destroy
   * must also be serialized per PV because CAJ shares channels by name: creating a channel while
   * the same-named channel is being destroyed hands back the closed channel.
   *
   * @param listener The PvListener to receive notifications
   * @param pv The PV to monitor
   * @throws CAException If unable to create the channel
   */
  public void addPv(PvListener listener, String pv) throws CAException {
    LOGGER.log(Level.FINEST, "addPv: {0} {1}", new Object[] {listener, pv});

    ChannelMonitor monitor;
    try {
      monitor =
          monitorMap.compute(
              pv,
              (k, existing) -> {
                ChannelMonitor m = existing;
                if (m == null) {
                  try {
                    m = new ChannelMonitor(pv, context, timeoutExecutor, callbackExecutor);
                  } catch (CAException e) {
                    throw new UncheckedCAException(e);
                  }
                }
                m.addListener(listener);
                return m;
              });
    } catch (UncheckedCAException e) {
      throw e.getCause();
    }

    clientMap.computeIfAbsent(listener, k -> ConcurrentHashMap.newKeySet()).add(pv);

    // ABSOLUTELY DO NOT CALL NOTIFY WHILE HOLDING A LOCK
    monitor.notifyCurrentState(listener);
  }

  /**
   * Removes the PV from the given listener. If the last listener on a given channel the monitor is
   * also removed.
   *
   * @param listener The PvListener
   * @param pv The PV to remove
   */
  public void removePv(PvListener listener, String pv) {
    LOGGER.log(Level.FINEST, "removePv: {0} {1}", new Object[] {listener, pv});

    Set<String> clientPvSet = clientMap.get(listener);
    if (clientPvSet != null) {
      clientPvSet.remove(pv);
    }

    monitorMap.computeIfPresent(
        pv,
        (k, monitor) -> {
          monitor.removeListener(listener);
          if (monitor.getListenerCount() > 0) {
            return monitor;
          }
          try {
            monitor.close();
          } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Unable to close monitor", e);
          }
          return null; // Last listener left; remove mapping
        });
  }

  /**
   * Removes the specified listener and unregisters any PVs the listener was interested in.
   *
   * @param listener The PvListener
   */
  public void removeAll(PvListener listener) {
    LOGGER.log(Level.FINEST, "removeAll: {0}", listener);
    Set<String> pvSet = clientMap.remove(listener);

    if (pvSet != null) {
      for (String pv : pvSet) {
        removePv(listener, pv);
      }
    }
  }

  /**
   * Returns a map of PVs to count of listeners for informational purposes.
   *
   * @return The PV to monitor map
   */
  public Map<String, ChannelMonitor> getMonitorMap() {
    // Really want readonly copy version here, but too lazy to make immutable ChannelMonitor
    return new HashMap<>(monitorMap);
  }

  /**
   * Return the number of channels open in the CA context, including any no monitor tracks, such as
   * one that failed to close.
   *
   * @return The channel count
   */
  public int getChannelCount() {
    return context == null ? 0 : context.getChannels().length;
  }

  /**
   * Returns an unmodifiable map of listeners to their PVs for informational purposes.
   *
   * @return The listener to PVs map
   */
  public Map<PvListener, Set<String>> getListenerMap() {
    return Collections.unmodifiableMap(clientMap);
  }

  /** Carries a checked CAException out of a ConcurrentHashMap remapping function. */
  private static class UncheckedCAException extends RuntimeException {
    UncheckedCAException(CAException cause) {
      super(cause);
    }

    @Override
    public synchronized CAException getCause() {
      return (CAException) super.getCause();
    }
  }
}
