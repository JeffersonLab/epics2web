package org.jlab.epics2web.websocket;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.websocket.CloseReason;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.PongMessage;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jlab.epics2web.Application;

/**
 * Controller for the EPICS web socket monitor.
 *
 * @author slominskir
 */
@ServerEndpoint(value = "/monitor", configurator = AuditServerEndpointConfigurator.class)
public class MonitorEndpoint {

  private static final Logger LOGGER = Logger.getLogger(MonitorEndpoint.class.getName());

  @OnOpen
  public void onOpen(Session session, EndpointConfig config) {
    // LOGGER.log(Level.FINEST, "open");

    Application.sessionManager.recordInteractionDate(session);

    if (session != null) {
      WebSocketAuditContext context = WebSocketAuditContext.getCurrentInstance();

      if (context != null) {
        Map<String, List<String>> headers = context.getHeaders();

        String ip = null;
        List<String> xForwardedForList = headers.get("X-Forwarded-For");
        String xForwardedFor = null;
        if (xForwardedForList != null && !xForwardedForList.isEmpty()) {
          xForwardedFor = xForwardedForList.get(0);
        }

        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
          String[] ipArray = xForwardedFor.split(",");
          ip = ipArray[0].trim(); // first one, if more than one
        }

        List<String> agentList = headers.get("user-agent");
        String agent = null;
        if (agentList != null && !agentList.isEmpty()) {
          agent = agentList.get(0);
        }
        if (agent == null) {
          agent = "Unknown";
        }

        if (ip == null) {
          ip = context.getRemoteAddr();
          if (ip == null) {
            ip = "Unknown";
          }
        }

        String name = "";
        String q = session.getQueryString();
        if (q != null) {
          String[] tokens = q.split("=");
          if (tokens.length == 2) {
            try {
              name = URLDecoder.decode(tokens[1], "UTF-8");
            } catch (UnsupportedEncodingException e) {
              LOGGER.log(
                  Level.WARNING,
                  "JVM doesn't support UTF-8 so can't decode clientName parameter",
                  e);
            }
          }
          if (name == null) {
            name = "";
          }
        }

        session.getUserProperties().put("agent", agent);
        session.getUserProperties().put("ip", ip);
        session.getUserProperties().put("name", name);

        // Try to prevent classloader leak
        WebSocketAuditContext.setCurrentInstance(null);
      }

      session.getUserProperties().put("droppedMessageCount", new AtomicLong());

      if (Application.WRITE_STRATEGY == WriteStrategy.ASYNC_QUEUE) {
        session.getUserProperties().put("isWriting", new AtomicBoolean(false));
        session.getUserProperties().put("writequeue", new ConcurrentLinkedQueue());
      } else if (Application.WRITE_STRATEGY == WriteStrategy.BLOCKING_QUEUE) {
        ArrayBlockingQueue<String> writequeue =
            new ArrayBlockingQueue<>(Application.WRITE_QUEUE_SIZE_LIMIT);
        session.getUserProperties().put("writequeue", writequeue);
        Future<?> writeThreadFuture = Application.writeFromBlockingQueue(session);
        session.getUserProperties().put("writeThreadFuture", writeThreadFuture);
      }

      Application.sessionManager.addClient(session);
    }
  }

  @OnClose
  public void onClose(Session session, CloseReason reason) {
    // LOGGER.log(Level.FINEST, "close; Reason: {0}", reason);
    if (session != null) {

      if (Application.WRITE_STRATEGY == WriteStrategy.BLOCKING_QUEUE) {
        Future<?> writeThreadFuture =
            (Future<?>) session.getUserProperties().get("writeThreadFuture");
        writeThreadFuture.cancel(true);
      }

      Application.sessionManager.removeClient(session);

      AtomicLong dropCount = (AtomicLong) session.getUserProperties().get("droppedMessageCount");
      Date lastUpdated = (Date) session.getUserProperties().get("lastUpdated");
      String host = (String) session.getUserProperties().get("ip");
      if (host == null) {
        host = (String) session.getUserProperties().get("remoteAddr");
      }

      if (dropCount.get() > 0) {
        LOGGER.log(
            Level.INFO,
            "Closing session; Host: {0}; Drop count: {1}; Last Interaction: {2}",
            new Object[] {host, dropCount.get(), lastUpdated});
      }
    }
  }

  @OnError
  public void onError(Session session, Throwable t) {
    // t.printStackTrace();
    LOGGER.log(Level.FINE, "WebSocket Error: {0}", t.getMessage());

    // Try to prevent classloader leak (this is done in onOpen but, might be skipped onError)
    WebSocketAuditContext.setCurrentInstance(null);
  }

  @OnMessage
  public void onPong(PongMessage message, Session session) {
    LOGGER.log(Level.FINEST, "WS Pong Received");
    Application.sessionManager.recordInteractionDate(session);
  }

  @OnMessage
  public String onMessage(String message, Session session) {
    // LOGGER.log(Level.FINEST, "Client message: {0}", message);

    if (Application.RESTARTING) {
      return null;
    }

    Application.sessionManager.recordInteractionDate(session);

    try (JsonReader reader = Json.createReader(new StringReader(message))) {
      JsonObject obj = reader.readObject();

      String type = obj.getString("type");

      if ("ping".equals(type)) {
        /*LOGGER.log(Level.FINEST, "ping recieved");*/
        Application.sessionManager.sendPong(session);
      } else if ("monitor".equals(type)) {
        JsonArray pvs = obj.getJsonArray("pvs");
        Set<String> pvSet = Application.sessionManager.getPvSetFromJson(pvs);

        Application.sessionManager.addPvs(session, pvSet);
      } else if ("clear".equals(type)) {
        JsonArray pvs = obj.getJsonArray("pvs");
        Set<String> pvSet = Application.sessionManager.getPvSetFromJson(pvs);

        Application.sessionManager.removePvs(session, pvSet);
      } else {
        LOGGER.log(Level.WARNING, "Unknown client request: {0}", message);
      }
    } catch (
        IllegalStateException e) { // state might be bad for various reasons so don't dump stack
      LOGGER.log(Level.INFO, "Unable to handle client message", e.getMessage());
    } catch (Exception e) {
      LOGGER.log(Level.WARNING, "Unable to handle client message: " + message, e);
    }
    return null;
  }
}
