package org.jlab.epics2web;

import com.cosylab.epics.caj.CAJContext;
import gov.aps.jca.CAException;
import gov.aps.jca.event.ContextExceptionEvent;
import gov.aps.jca.event.ContextExceptionListener;
import gov.aps.jca.event.ContextMessageEvent;
import gov.aps.jca.event.ContextMessageListener;
import gov.aps.jca.event.ContextVirtualCircuitExceptionEvent;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.annotation.WebListener;
import jakarta.websocket.RemoteEndpoint;
import jakarta.websocket.SendHandler;
import jakarta.websocket.SendResult;
import jakarta.websocket.Session;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jlab.epics2web.epics.ChannelManager;
import org.jlab.epics2web.epics.ContextFactory;
import org.jlab.epics2web.websocket.WebSocketSessionManager;
import org.jlab.epics2web.websocket.WriteQueue;
import org.jlab.epics2web.websocket.WriteStrategy;

/**
 * Main class that ties into application lifecycle; creates and destroys key resources.
 *
 * @author slominskir
 */
@WebListener
public class Application implements ServletContextListener {

  public static final WriteStrategy WRITE_STRATEGY = WriteStrategy.BLOCKING_QUEUE;
  public static final int WRITE_QUEUE_SIZE_LIMIT = 2000;

  public static ChannelManager channelManager = null;
  public static WebSocketSessionManager sessionManager = null;

  private static final int TIMEOUT_EXECUTOR_POOL_SIZE = 1;
  private static final Logger LOGGER = Logger.getLogger(Application.class.getName());

  /** How often the server pings WebSocket sessions and closes stale ones. */
  static final long PING_INTERVAL_SECONDS =
      getSecondsFromEnv("WEBSOCKET_PING_INTERVAL_SECONDS", 30);

  /** A WebSocket session with no message or pong for this long is closed. */
  static final long TIMEOUT_SECONDS = getSecondsFromEnv("WEBSOCKET_TIMEOUT_SECONDS", 60);

  /**
   * How long a write to a WebSocket session may block, such as when the client stops reading,
   * before Tomcat closes the session. Tomcat's own default is 20 seconds.
   */
  public static final long SEND_TIMEOUT_SECONDS =
      getSecondsFromEnv("WEBSOCKET_SEND_TIMEOUT_SECONDS", 20);

  /** How long a PV may be disconnected before the healthcheck reports it. */
  public static final long HEALTHCHECK_GRACE_SECONDS =
      getSecondsFromEnv("HEALTHCHECK_GRACE_SECONDS", 30);

  private static ScheduledExecutorService timeoutExecutor = null;
  private static ExecutorService callbackExecutor = null;
  private static ExecutorService writerExecutor = null;
  private static ScheduledExecutorService sessionCheckExecutor = null;
  private static ExecutorService pingExecutor = null;
  private static ContextFactory factory = null;
  private static volatile CAJContext context = null;

  /** Read a positive whole number of seconds from an environment variable. */
  static long getSecondsFromEnv(String name, long defaultValue) {
    return parseSeconds(name, System.getenv(name), defaultValue);
  }

  static long parseSeconds(String name, String value, long defaultValue) {
    if (value == null || value.isBlank()) {
      return defaultValue;
    }

    try {
      long seconds = Long.parseLong(value.trim());
      if (seconds > 0) {
        return seconds;
      }
    } catch (NumberFormatException e) {
      // Fall through to the warning
    }

    LOGGER.log(
        Level.WARNING,
        "{0} must be a positive whole number of seconds, not \"{1}\"; using {2}",
        new Object[] {name, value, defaultValue});
    return defaultValue;
  }

  @SuppressWarnings("unchecked")
  public static Future<?> writeFromBlockingQueue(Session session) {
    return writerExecutor.submit(
        new Runnable() {
          @Override
          public void run() {
            final String id = session.getId() + " / " + session.getUserProperties().get("ip");
            final WriteQueue writequeue =
                (WriteQueue) session.getUserProperties().get("writequeue");
            try {
              while (true) {
                if (session.isOpen()) {
                  String msg = writequeue.take(); // Block until msg to deliver or queue closed

                  if (msg == null) {
                    LOGGER.log(Level.FINEST, "Session {0} closed; shutting down write thread", id);
                    break;
                  } else {
                    try {
                      session.getBasicRemote().sendText(msg);
                    } catch (IllegalStateException
                        | IOException e) { // If session closes between time session.isOpen() and
                      // sentText(msg) then you'll get this exception.  Not an issue.
                      LOGGER.log(Level.FINEST, "Unable to send message to " + id, e);

                      if (!session.isOpen()) {
                        LOGGER.log(
                            Level.FINEST,
                            "Session closed after write exception; shutting down write thread");
                        break;
                      }
                    }
                  }
                } else {
                  LOGGER.log(Level.FINEST, "Session {0} closed; shutting down write thread", id);
                  break;
                }
              }
            } catch (InterruptedException e) {
              LOGGER.log(
                  Level.FINEST,
                  "Shutting down {0} writer thread as requested by InterruptException",
                  id);
            }
          }
        });
  }

  @Override
  public void contextInitialized(ServletContextEvent sce) {
    LOGGER.log(Level.INFO, ">>>>>>>>>>>>>>>>>>>>>>>>>> CONTEXT INITIALIZED");

    factory = new ContextFactory();
    try {
      context = factory.newContext();
      // context.getLogger().setLevel(Level.FINE);
    } catch (Exception e) {
      LOGGER.log(Level.SEVERE, "Unable to obtain EPICS CA context", e);
    }
    timeoutExecutor =
        Executors.newScheduledThreadPool(
            TIMEOUT_EXECUTOR_POOL_SIZE, new CustomPrefixThreadFactory("CA-Timeout-"));
    callbackExecutor = Executors.newCachedThreadPool(new CustomPrefixThreadFactory("Callback-"));
    writerExecutor =
        Executors.newCachedThreadPool(new CustomPrefixThreadFactory("Web-Socket-Writer-"));
    sessionCheckExecutor =
        Executors.newSingleThreadScheduledExecutor(
            new CustomPrefixThreadFactory("Web-Socket-Session-Check-"));
    pingExecutor = Executors.newCachedThreadPool(new CustomPrefixThreadFactory("Web-Socket-Ping-"));
    channelManager = new ChannelManager(context, timeoutExecutor, callbackExecutor);
    sessionManager =
        new WebSocketSessionManager(channelManager, Duration.ofSeconds(TIMEOUT_SECONDS));

    LOGGER.log(
        Level.INFO,
        "Pinging WebSocket sessions every {0} s; closing those with no message or pong for {1} s,"
            + " or with a write blocked for {2} s",
        new Object[] {PING_INTERVAL_SECONDS, TIMEOUT_SECONDS, SEND_TIMEOUT_SECONDS});
    if (TIMEOUT_SECONDS <= PING_INTERVAL_SECONDS) {
      LOGGER.log(
          Level.WARNING,
          "WEBSOCKET_TIMEOUT_SECONDS should be longer than WEBSOCKET_PING_INTERVAL_SECONDS, or "
              + "clients that only answer pings will be closed");
    }

    sessionCheckExecutor.scheduleWithFixedDelay(
        () -> {
          try {
            sessionManager.purgeStaleSessions(pingExecutor);
            sessionManager.pingAllSessions(pingExecutor);
          } catch (RuntimeException e) { // An exception would cancel the schedule
            LOGGER.log(Level.WARNING, "Unable to check sessions", e);
          }
        },
        PING_INTERVAL_SECONDS,
        PING_INTERVAL_SECONDS,
        TimeUnit.SECONDS);

    try {
      registerContextListeners(context);
    } catch (Exception e) {
      LOGGER.log(Level.SEVERE, "Unable to register context callbacks", e);
    }

    if (WRITE_STRATEGY == WriteStrategy.ASYNC_QUEUE) {
      writerExecutor.execute(
          new Runnable() {
            @Override
            @SuppressWarnings("unchecked")
            public void run() {
              while (true) {
                try {
                  for (Session session : sessionManager.toSet()) {
                    if (session.isOpen()) {
                      AtomicBoolean isWriting =
                          (AtomicBoolean) session.getUserProperties().get("isWriting");
                      boolean updated = isWriting.compareAndSet(false, true);
                      if (updated) {
                        ConcurrentLinkedQueue<String> writequeue =
                            (ConcurrentLinkedQueue<String>)
                                session.getUserProperties().get("writequeue");
                        String msg = writequeue.poll();
                        if (msg == null) {
                          isWriting.compareAndSet(true, false);
                        } else {
                          RemoteEndpoint.Async a = session.getAsyncRemote();
                          // LOGGER.log(Level.INFO, "Sending msg: {0}", msg);
                          a.sendText(
                              msg,
                              new SendHandler() {
                                @Override
                                public void onResult(SendResult result) {
                                  boolean u = isWriting.compareAndSet(true, false);
                                  if (!u) {
                                    LOGGER.log(Level.WARNING, "No need to clear isWriting");
                                  }
                                  if (!result.isOK()) {
                                    LOGGER.log(
                                        Level.FINEST,
                                        "Unable to send message",
                                        result.getException());
                                  }
                                }
                              });
                        }
                      }
                    }
                  }
                } catch (Exception e) {
                  LOGGER.log(Level.WARNING, "Unable to write message for session", e);
                }
                if (Thread.interrupted()) {
                  LOGGER.log(Level.WARNING, "Writer Thread interrupted; shutting it down");
                  break;
                }

                LockSupport.parkNanos(this, 10);
              }
            }
          });
    }
  }

  @Override
  public void contextDestroyed(ServletContextEvent sce) {
    LOGGER.log(Level.INFO, ">>>>>>>>>>>>>>>>>>>>>>>>>> CONTEXT DESTROYED");

    if (context != null) {
      try {
        context.destroy();
      } catch (CAException e) {
        LOGGER.log(Level.WARNING, "Unable to destroy Context", e);
      }
    }

    if (timeoutExecutor != null) {
      timeoutExecutor.shutdown();
    }

    if (callbackExecutor != null) {
      callbackExecutor.shutdown();
    }

    if (writerExecutor != null) {
      writerExecutor.shutdownNow();
    }

    if (sessionCheckExecutor != null) {
      sessionCheckExecutor.shutdownNow();
    }

    if (pingExecutor != null) {
      pingExecutor.shutdownNow();
    }

    if (timeoutExecutor != null) {
      try {
        if (!timeoutExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
          LOGGER.log(Level.WARNING, "Timeout Thread ExecutorService is not stopping...");
          timeoutExecutor.shutdownNow();
        }
      } catch (InterruptedException e) {
        LOGGER.log(Level.SEVERE, "Interrupted while waiting for threads to stop", e);
      }
    }

    if (callbackExecutor != null) {
      try {
        if (!callbackExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
          LOGGER.log(Level.WARNING, "Callback Thread ExecutorService is not stopping...");
          callbackExecutor.shutdownNow();
        }
      } catch (InterruptedException e) {
        LOGGER.log(Level.SEVERE, "Interrupted while waiting for threads to stop", e);
      }
    }

    if (writerExecutor != null) {
      try {
        if (!writerExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
          LOGGER.log(Level.WARNING, "Writer Thread ExecutorService is not stopping...");
        }
      } catch (InterruptedException e) {
        LOGGER.log(Level.SEVERE, "Interrupted while waiting for threads to stop", e);
      }
    }
  }

  private void registerContextListeners(CAJContext c) throws CAException {
    c.addContextExceptionListener(
        new ContextExceptionListener() {
          @Override
          public void contextException(ContextExceptionEvent ev) {
            LOGGER.log(Level.SEVERE, "EPICS CA Context Exception: {0}", ev.getMessage());
            LOGGER.log(
                Level.SEVERE,
                "Channel: {0}",
                ev.getChannel() == null ? "N/A" : ev.getChannel().getName());
          }

          @Override
          public void contextVirtualCircuitException(ContextVirtualCircuitExceptionEvent ev) {
            LOGGER.log(
                Level.SEVERE,
                "EPICS CA Context Virtual Circuit Exception: Status: {0}, Address: {1}, Fatal: {2}",
                new Object[] {ev.getStatus(), ev.getVirtualCircuit(), ev.getStatus().isFatal()});
          }
        });

    c.addContextMessageListener(
        new ContextMessageListener() {
          @Override
          public void contextMessage(ContextMessageEvent ev) {
            LOGGER.log(Level.WARNING, "EPICS CA Context Messge Event: {0}", ev.getMessage());
          }
        });
  }
}
