package org.jlab.epics2web;

import org.jlab.epics2web.websocket.WebSocketSessionManager;
import org.jlab.epics2web.epics.ChannelMonitorManager;
import org.jlab.epics2web.epics.ContextFactory;
import com.cosylab.epics.caj.CAJContext;
import gov.aps.jca.CAException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;

/**
 * Main class that ties into application lifecycle; creates and destroys key resources.
 * 
 * @author ryans
 */
@WebListener
public class Application implements ServletContextListener {

    public static ChannelMonitorManager channelManager = null;
    public static WebSocketSessionManager sessionManager = new WebSocketSessionManager();
    
    private static final int EXECUTOR_POOL_SIZE = 2;
    private static final Logger LOGGER = Logger.getLogger(Application.class.getName());
    private static ScheduledExecutorService executor = null;
    private static ContextFactory factory = null;
    private static CAJContext context = null;

    @Override
    public void contextInitialized(ServletContextEvent sce) {
        LOGGER.log(Level.INFO, ">>>>>>>>>>>>>>>>>>>>>>>>>> CONTEXT INITIALIZED");

        factory = new ContextFactory();
        try {
            context = factory.getContext();
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Unable to obtain EPICS CA context", e);
        }
        executor = Executors.newScheduledThreadPool(EXECUTOR_POOL_SIZE);
        channelManager = new ChannelMonitorManager(context, executor);
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
        
        if(factory != null) {
            factory.shutdown();
        }

        if (executor != null) {
            executor.shutdown();

            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    LOGGER.log(Level.WARNING, "Thread ExecutorService is not stopping...");
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                LOGGER.log(Level.SEVERE, "Interrupted while waiting for threads to stop", e);
            }
        }
    }

}
