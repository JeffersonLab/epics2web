package org.jlab.epics2web;

import org.jlab.epics2web.websocket.WebSocketSessionManager;
import org.jlab.epics2web.epics.ChannelManager;
import org.jlab.epics2web.epics.ContextFactory;
import com.cosylab.epics.caj.CAJContext;
import com.cosylab.epics.caj.impl.CATransport;
import com.cosylab.epics.caj.impl.Transport;
import gov.aps.jca.CAException;
import gov.aps.jca.CAStatus;
import gov.aps.jca.event.ContextExceptionEvent;
import gov.aps.jca.event.ContextExceptionListener;
import gov.aps.jca.event.ContextMessageEvent;
import gov.aps.jca.event.ContextMessageListener;
import gov.aps.jca.event.ContextVirtualCircuitExceptionEvent;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
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
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;
import javax.websocket.RemoteEndpoint;
import javax.websocket.SendHandler;
import javax.websocket.SendResult;
import javax.websocket.Session;
import org.jlab.epics2web.websocket.WriteStrategy;

/**
 * Main class that ties into application lifecycle; creates and destroys key
 * resources.
 *
 * @author slominskir
 */
@WebListener
public class Application implements ServletContextListener {

    public static final WriteStrategy WRITE_STRATEGY = WriteStrategy.BLOCKING_QUEUE;
    public static final int WRITE_QUEUE_SIZE_LIMIT = 2000;

    public static ChannelManager channelManager = null;
    public static WebSocketSessionManager sessionManager = new WebSocketSessionManager();

    private static final int TIMEOUT_EXECUTOR_POOL_SIZE = 1;
    private static final Logger LOGGER = Logger.getLogger(Application.class.getName());
    private static ScheduledExecutorService timeoutExecutor = null;
    private static ExecutorService writerExecutor = null;
    private static ExecutorService resetExecutor = null;
    private static ContextFactory factory = null;
    private static volatile CAJContext context = null;

    public static volatile boolean RESTARTING = false;

    public static Future<?> writeFromBlockingQueue(Session session) {
        return writerExecutor.submit(new Runnable() {
            @Override
            public void run() {
                final ArrayBlockingQueue<String> writequeue = (ArrayBlockingQueue<String>) session.getUserProperties().get("writequeue");
                try {
                    while (true) {
                        if (session.isOpen()) {
                            String msg = writequeue.take(); // Block until msg to deliver
                            if (msg != null) {
                                try {
                                    session.getBasicRemote().sendText(msg);
                                } catch (IllegalStateException e) { // If session closes between time session.isOpen() and sentText(msg) then you'll get this exception.  Not an issue.
                                    LOGGER.log(Level.INFO, "Unable to send message", e.getMessage());
                                } catch (IOException e) {
                                    LOGGER.log(Level.WARNING, "Unable to send message", e);
                                }
                            }
                        } else {
                            sessionManager.removeClient(session);

                            LOGGER.log(Level.INFO, "Session closed; shutting down write thread");
                            break;
                        }
                    }
                } catch (InterruptedException e) {
                    //LOGGER.log(Level.INFO, "Shutting down writer thread as requested", e);
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
            //context.getLogger().setLevel(Level.FINE);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Unable to obtain EPICS CA context", e);
        }
        timeoutExecutor = Executors.newScheduledThreadPool(TIMEOUT_EXECUTOR_POOL_SIZE, new CustomPrefixThreadFactory("CA-Timeout-"));
        writerExecutor = Executors.newCachedThreadPool(new CustomPrefixThreadFactory("Web-Socket-Writer-"));
        resetExecutor = Executors.newSingleThreadExecutor(new CustomPrefixThreadFactory("Resetter-"));
        channelManager = new ChannelManager(context, timeoutExecutor);

        try {
            registerContextListeners(context);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Unable to register context callbacks", e);
        }

        if (WRITE_STRATEGY == WriteStrategy.ASYNC_QUEUE) {
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

        if (writerExecutor != null) {
            writerExecutor.shutdownNow();
        }

        if (resetExecutor != null) {
            resetExecutor.shutdown();
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

    private void registerContextListeners(CAJContext c) throws CAException {
        c.addContextExceptionListener(new ContextExceptionListener() {
            @Override
            public void contextException(ContextExceptionEvent ev) {
                LOGGER.log(Level.SEVERE, "EPICS CA Context Exception: {0}", ev.getMessage());
                LOGGER.log(Level.SEVERE, "Channel: {0}", ev.getChannel() == null ? "N/A" : ev.getChannel().getName());
            }

            @Override
            public void contextVirtualCircuitException(ContextVirtualCircuitExceptionEvent ev) {
                LOGGER.log(Level.SEVERE, "EPICS CA Context Virtual Circuit Exception: Status: {0}, Address: {1}, Fatal: {2}", new Object[]{ev.getStatus(), ev.getVirtualCircuit(), ev.getStatus().isFatal()});

                LOGGER.log(Level.SEVERE, "Source: {0}", ev.getSource());

                //int statusCode = ev.getStatus().getStatusCode();
                //if(statusCode == CAStatus.UNRESPTMO.getStatusCode()) {
                // Only do a reset if Unresponsive?
                //}
                LOGGER.log(Level.SEVERE, "ATTEMPTING A CONTEXT RESET");

                /*InetAddress ip = ev.getVirtualCircuit(); 
                    int port = 5064;
                    InetSocketAddress ipAndPort = new InetSocketAddress(ip, port);
                    Transport[] transportArray = context.getTransportRegistry().get(ipAndPort);
                    
                    if(transportArray != null && transportArray.length == 1 && transportArray[0] instanceof CATransport) {
                        CATransport trans = (CATransport)transportArray[0];
                        trans.changedTransport();
                        trans.close(true);
                    }*/
                try {
                    context = factory.newContext();
                    registerContextListeners(context);

                    // We run the reset in a separate thread as the contextVirtualCircuitException is generally called from Timer thread, which is hold locks in a bad way
                    resetExecutor.execute(new Runnable() {
                        @Override
                        public void run() {
                            LOGGER.log(Level.INFO, "Starting reset procedure");
                            try {
                                RESTARTING = true;
                                channelManager.reset(context);
                            } finally {
                                RESTARTING = false;
                            }
                        }
                    });

                } catch (CAException e) {
                    LOGGER.log(Level.SEVERE, "Unable to reset context", e);
                }
            }
        });

        c.addContextMessageListener(new ContextMessageListener() {
            @Override
            public void contextMessage(ContextMessageEvent ev) {
                LOGGER.log(Level.WARNING, "EPICS CA Context Messge Event: {0}", ev.getMessage());
            }
        });
    }
}
