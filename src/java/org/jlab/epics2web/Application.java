package org.jlab.epics2web;

import org.jlab.epics2web.websocket.WebSocketSessionManager;
import org.jlab.epics2web.epics.ChannelManager;
import org.jlab.epics2web.epics.ContextFactory;
import com.cosylab.epics.caj.CAJContext;
import gov.aps.jca.CAException;
import gov.aps.jca.event.ContextExceptionEvent;
import gov.aps.jca.event.ContextExceptionListener;
import gov.aps.jca.event.ContextMessageEvent;
import gov.aps.jca.event.ContextMessageListener;
import gov.aps.jca.event.ContextVirtualCircuitExceptionEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;
import javax.websocket.RemoteEndpoint;
import javax.websocket.SendHandler;
import javax.websocket.SendResult;
import javax.websocket.Session;

/**
 * Main class that ties into application lifecycle; creates and destroys key
 * resources.
 *
 * @author ryans
 */
@WebListener
public class Application implements ServletContextListener {

    public static final boolean USE_QUEUE = false;

    public static ChannelManager channelManager = null;
    public static WebSocketSessionManager sessionManager = new WebSocketSessionManager();

    private static final int TIMEOUT_EXECUTOR_POOL_SIZE = 1;
    private static final Logger LOGGER = Logger.getLogger(Application.class.getName());
    private static ScheduledExecutorService timeoutExecutor = null;
    private static ExecutorService writerExecutor = null;
    private static ContextFactory factory = null;
    private static CAJContext context = null;

    @Override
    public void contextInitialized(ServletContextEvent sce) {
        LOGGER.log(Level.INFO, ">>>>>>>>>>>>>>>>>>>>>>>>>> CONTEXT INITIALIZED");

        factory = new ContextFactory();
        try {
            context = factory.getContext();

            context.addContextExceptionListener(new ContextExceptionListener() {
                @Override
                public void contextException(ContextExceptionEvent ev) {
                    LOGGER.log(Level.SEVERE, "EPICS CA Context Exception: {0}", ev.getMessage());
                    LOGGER.log(Level.SEVERE, "Channel: {0}", ev.getChannel() == null ? "N/A" : ev.getChannel().getName());
                }

                @Override
                public void contextVirtualCircuitException(ContextVirtualCircuitExceptionEvent ev) {
                    LOGGER.log(Level.SEVERE, "EPICS CA Context Virtual Circuit Exception: Status: {0}, Address: {1}, Fatal: {2}", new Object[]{ev.getStatus(), ev.getVirtualCircuit(), ev.getStatus().isFatal()});
                }
            });
            
            context.addContextMessageListener(new ContextMessageListener() {
                @Override
                public void contextMessage(ContextMessageEvent ev) {
                    LOGGER.log(Level.WARNING, "EPICS CA Context Messge Event: {0}", ev.getMessage());
                }
            });
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Unable to obtain EPICS CA context", e);
        }
        timeoutExecutor = Executors.newScheduledThreadPool(TIMEOUT_EXECUTOR_POOL_SIZE, new CustomPrefixThreadFactory("CA-Timeout-"));
        writerExecutor = Executors.newSingleThreadExecutor(new CustomPrefixThreadFactory("Web-Socket-Writer-"));
        channelManager = new ChannelManager(context, timeoutExecutor);

        if (USE_QUEUE) {
            writerExecutor.execute(new Runnable() {
                @Override
                @SuppressWarnings("unchecked") 
                public void run() {
                    while (true) {
                        try {
                            for (Session session : sessionManager.toSet()) {
                                if (session.isOpen()) {
                                    AtomicBoolean isWriting = (AtomicBoolean) session.getUserProperties().get("isWriting");
                                    boolean updated = isWriting.compareAndSet(false, true);
                                    if (updated) {
                                        ConcurrentLinkedQueue<String> writequeue = (ConcurrentLinkedQueue<String>) session.getUserProperties().get("writequeue");
                                        String msg = writequeue.poll();
                                        if (msg == null) {
                                            isWriting.compareAndSet(true, false);
                                        } else {
                                            RemoteEndpoint.Async a = session.getAsyncRemote();
                                            //LOGGER.log(Level.INFO, "Sending msg: {0}", msg);
                                            a.sendText(msg, new SendHandler() {
                                                @Override
                                                public void onResult(SendResult result) {
                                                    boolean u = isWriting.compareAndSet(true, false);
                                                    if (!u) {
                                                        LOGGER.log(Level.WARNING, "No need to clear isWriting");
                                                    }
                                                    if (!result.isOK()) {
                                                        LOGGER.log(Level.FINEST, "Unable to send message", result.getException());
                                                        sessionManager.removeClient(session);
                                                    }
                                                }
                                            });
                                        }
                                    }
                                } else {
                                    sessionManager.removeClient(session);
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

        if (context != null && factory != null) {
            try {
                factory.returnContext(context);
            } catch (CAException e) {
                LOGGER.log(Level.WARNING, "Unable to return EPICS CA Context", e);
            }
        }

        if (factory != null) {
            factory.shutdown();
        }

        if (timeoutExecutor != null) {
            timeoutExecutor.shutdown();
        }

        if (writerExecutor != null) {
            writerExecutor.shutdownNow();
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
}
