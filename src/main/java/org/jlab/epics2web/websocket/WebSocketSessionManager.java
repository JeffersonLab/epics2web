package org.jlab.epics2web.websocket;

import gov.aps.jca.CAException;
import gov.aps.jca.dbr.DBR;
import gov.aps.jca.dbr.DBRType;
import jakarta.json.*;
import jakarta.websocket.Session;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jlab.epics2web.Application;
import org.jlab.epics2web.epics.ChannelManager;
import org.jlab.epics2web.epics.PvListener;

/**
 * Manages web socket sessions and ties them to channel access monitors.
 *
 * @author slominskir
 */
public class WebSocketSessionManager {

  private static final Logger LOGGER = Logger.getLogger(WebSocketSessionManager.class.getName());

  private final JsonBuilderFactory factory = Json.createBuilderFactory(null);

  private final ChannelManager channelManager;

  /** A session with no message or pong for this long is closed. */
  private final Duration staleAfter;

  /*ConcurrentHashMap provides thread safety on map of listeners*/
  final Map<Session, WebSocketSessionMonitor> listenerMap = new ConcurrentHashMap<>();

  /**
   * Create a new WebSocketSessionManager.
   *
   * @param channelManager The channel manager that monitors PVs for sessions
   * @param staleAfter How long a session may go without a message or pong before it is closed
   */
  public WebSocketSessionManager(ChannelManager channelManager, Duration staleAfter) {
    this.channelManager = channelManager;
    this.staleAfter = staleAfter;
  }

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

  /**
   * Close and remove sessions that are closed already, or that haven't sent a message or pong for
   * staleAfter. Run periodically, together with pingAllSessions.
   *
   * @param executor Runs the closes, which can block on an unresponsive client
   */
  public void purgeStaleSessions(Executor executor) {
    Instant cutoff = Instant.now().minus(staleAfter);

    for (Session s : listenerMap.keySet()) {
      if (isStale(s, cutoff)) {
        LOGGER.log(Level.INFO, "Expiring session: {0}", s.getId());
        removeClient(s);
        executor.execute(() -> close(s));
      }
    }
  }

  private boolean isStale(Session s, Instant cutoff) {
    if (!s.isOpen()) {
      return true;
    }

    Date lastUpdated = (Date) s.getUserProperties().get("lastUpdated");

    return lastUpdated == null || lastUpdated.toInstant().isBefore(cutoff);
  }

  /**
   * Send a WebSocket ping to every session. Clients answer with a pong, which records an
   * interaction, so purgeStaleSessions can tell live clients from dead ones whether or not they
   * send pings of their own. A session the ping can't be sent to is closed and removed.
   *
   * @param executor Runs the pings, one task per session, so an unresponsive client doesn't delay
   *     the others
   */
  public void pingAllSessions(Executor executor) {
    for (Session s : listenerMap.keySet()) {
      executor.execute(
          () -> {
            try {
              sendWsPing(s);
            } catch (IllegalArgumentException | IllegalStateException | IOException e) {
              LOGGER.log(
                  Level.INFO,
                  "Unable to send WS ping to session {0}: {1}",
                  new Object[] {s.getId(), e});
              removeClient(s);
              close(s);
            }
          });
    }
  }

  private void close(Session s) {
    if (s.isOpen()) {
      try {
        s.close();
      } catch (IOException e) {
        LOGGER.log(Level.WARNING, "Unable to close session", e);
      }
    }
  }

  public void sendWsPing(Session session) throws IllegalArgumentException, IOException {
    if (session.isOpen()) {
      synchronized (session) {
        session.getBasicRemote().sendPing(ByteBuffer.allocate(0));
      }
    } else {
      LOGGER.log(Level.FINEST, "session is closed: {0}", session);
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
    listenerMap.put(session, new WebSocketSessionMonitor(session, this));
  }

  /**
   * Remove a client from the manager's list of clients.
   *
   * @param session The session (client) to remove
   */
  public void removeClient(Session session) {
    WebSocketSessionMonitor listener = listenerMap.remove(session);

    if (listener != null) {
      channelManager.removeAll(listener);
    }
  }

  /**
   * Monitor the provided set of PVs for the specified client.
   *
   * @param session The client session
   * @param pvSet The set of PVs
   */
  public void addPvs(Session session, Set<String> pvSet) {
    WebSocketSessionMonitor listener = listenerMap.get(session);

    if (listener == null) {
      LOGGER.log(Level.FINEST, "Ignoring add PV request from removed session");
      return;
    }

    if (pvSet != null) {
      // Make sure empty string isn't included as a PV as that is invalid and is ignored
      boolean emptyIncluded = pvSet.remove("");

      if (emptyIncluded) {
        LOGGER.log(Level.FINEST, "Empty string ignored in add PV request");
      }

      for (String pv : pvSet) {
        try {
          channelManager.addPv(listener, pv);
        } catch (CAException e) {
          LOGGER.log(Level.WARNING, "Unable to addPv: " + pv, e);
          // TODO: Retry?
        }
      }

      // If the session was removed meanwhile, its removeAll may have missed these PVs
      if (listenerMap.get(session) != listener) {
        channelManager.removeAll(listener);
      }
    }
  }

  /**
   * Stop monitoring the provided PVs for the specified client.
   *
   * @param session The client session
   * @param pvSet The set of PVs
   */
  public void removePvs(Session session, Set<String> pvSet) {
    WebSocketSessionMonitor listener = listenerMap.get(session);

    if (listener == null) {
      return;
    }

    // Make sure empty string isn't included as a PV as that is invalid and is ignored
    boolean emptyIncluded = pvSet.remove("");

    if (emptyIncluded) {
      LOGGER.log(Level.FINEST, "Empty string ignored in remove PV request");
    }

    for (String pv : pvSet) {
      channelManager.removePv(listener, pv);
    }
  }

  /**
   * Get a map of sessions to PVs.
   *
   * @return The map
   */
  public Map<SessionInfo, Set<String>> getClientMap() {
    Map<PvListener, Set<String>> pvMap = channelManager.getListenerMap();
    Map<SessionInfo, Set<String>> clientMap = new HashMap<>();

    for (Map.Entry<Session, WebSocketSessionMonitor> entry : listenerMap.entrySet()) {
      Session session = entry.getKey();
      Set<String> pvSet = pvMap.getOrDefault(entry.getValue(), Set.of());

      if (session.isOpen()) {
        String id = null;

        try {
          id = session.getId();
          String ip = (String) session.getUserProperties().get("ip");
          String name = (String) session.getUserProperties().get("name");
          String agent = (String) session.getUserProperties().get("agent");
          AtomicLong droppedMessageCount =
              (AtomicLong) session.getUserProperties().get("droppedMessageCount");

          SessionInfo info = new SessionInfo(id, ip, name, agent, droppedMessageCount.get());

          clientMap.put(info, pvSet);
        } catch (Exception e) {
          // Even id may be null if closed before getId() called.  Oh well.
          LOGGER.log(Level.FINEST, "Session '{0}' closed while preparing info report", id);
          // Ignore
        }
      }
    }

    return clientMap;
  }

  public Set<Session> toSet() {
    return new HashSet<>(listenerMap.keySet());
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
  public void sendInfo(
      Session session,
      String pv,
      boolean couldConnect,
      DBRType type,
      Integer count,
      String[] enumLabels) {
    JsonObjectBuilder builder = factory.createObjectBuilder();

    builder.add("type", "info").add("pv", pv).add("connected", couldConnect);

    if (couldConnect) {
      builder.add("datatype", type.getName()).add("count", count);

      if (enumLabels != null) {
        JsonArrayBuilder arrBuilder = Json.createArrayBuilder();
        for (String label : enumLabels) {
          arrBuilder.add(label);
        }

        builder.add("enum-labels", arrBuilder);
      }
    }

    JsonObject obj = builder.build();
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
    JsonObjectBuilder builder = factory.createObjectBuilder();

    builder.add("type", "update").add("pv", pv);
    channelManager.addValueToJSON(builder, dbr);
    JsonObject obj = builder.build();
    String msg = obj.toString();
    send(session, pv, msg);
  }

  @SuppressWarnings("unchecked")
  public void send(Session session, String pv, String msg) {
    if (session.isOpen()) {
      String id = session.toString();
      if (Application.WRITE_STRATEGY == WriteStrategy.ASYNC_QUEUE) {
        ConcurrentLinkedQueue<String> writequeue =
            (ConcurrentLinkedQueue<String>) session.getUserProperties().get("writequeue");

        if (writequeue.size() > Application.WRITE_QUEUE_SIZE_LIMIT) {
          AtomicLong dropCount =
              (AtomicLong) session.getUserProperties().get("droppedMessageCount");
          long count =
              dropCount.getAndIncrement()
                  + 1; // getAndIncrement is actually returning previous value, not newly updated,
          // so we add 1.
          // Limit log file output by only reporting when thresholds are reached
          if (count == 1 || count == 1000 || count == 10000 || count == 100000) {
            LOGGER.log(
                Level.FINEST,
                "Session {0} queue full (limit={1}); Dropping pv {2} message: {3}; total dropped: {4}",
                new Object[] {id, Application.WRITE_QUEUE_SIZE_LIMIT, pv, msg, count});
          }
        } else {
          writequeue.offer(msg);
        }
      } else if (Application.WRITE_STRATEGY == WriteStrategy.BLOCKING_QUEUE) {
        ArrayBlockingQueue<String> writequeue =
            (ArrayBlockingQueue<String>) session.getUserProperties().get("writequeue");

        // TODO: should be using a fancy custom BlockingQueue that prioritizes info messages and
        // also replaces queued update messages with most recent update (don't notify of stale
        // updates - just move on to fresh updates)
        // TODO: it seems message queue filling up is likely a sign connection to client is bad and
        // maybe we should just close socket?

        boolean success = writequeue.offer(msg);

        if (!success) {
          AtomicLong dropCount =
              (AtomicLong) session.getUserProperties().get("droppedMessageCount");
          long count =
              dropCount.getAndIncrement()
                  + 1; // getAndIncrement is actually returning previous value, not newly updated,
          // so we add 1.
          // Limit log file output by only reporting when thresholds are reached
          if (count == 1 || count == 1000 || count == 10000 || count == 100000) {
            LOGGER.log(
                Level.FINEST,
                "Session {0} queue full (limit={1}); Dropping pv {2} message: {3}; total dropped: {4}",
                new Object[] {id, Application.WRITE_QUEUE_SIZE_LIMIT, pv, msg, count});
          }
        }
      } else {
        try {
          synchronized (session) {
            session.getBasicRemote().sendText(msg);
          }
        } catch (
            IllegalStateException
                e) { // If session closes between time session.isOpen() and sentText(msg) then
          // you'll get this exception.  Not an issue.
          LOGGER.log(Level.INFO, "Unable to send message: {0}", e.getMessage());
        } catch (IOException e) {
          LOGGER.log(Level.WARNING, "Unable to send message", e);
        }
      }
    }
  }
}
