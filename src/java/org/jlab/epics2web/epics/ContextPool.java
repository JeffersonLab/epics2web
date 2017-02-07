package org.jlab.epics2web.epics;

import com.cosylab.epics.caj.CAJContext;
import gov.aps.jca.CAException;
import gov.aps.jca.JCALibrary;
import gov.aps.jca.configuration.DefaultConfiguration;
import java.util.LinkedList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Provides a pool of EPICS channel access contexts.
 * 
 * @author ryans
 */
public class ContextPool {

    private static final Logger logger = Logger.getLogger(ContextPool.class.getName());
    private final int WAIT_TIMEOUT;
    private final int MAX_SIZE;
    private List<CAJContext> available;
    private List<CAJContext> used;
    private final DefaultConfiguration config;
    
    /**
     * Construct a ContextPool with the specified configuration.
     * 
     * @param config the configuration.
     * @throws CAException if unable to construct a context pool.
     */
    public ContextPool(DefaultConfiguration config) throws CAException {
        logger.log(Level.FINEST, "Creating EPICS ContextPool");
        
        this.config = config;
       
        available = new LinkedList<>();
        used = new LinkedList<>();

        config.setAttribute("class", JCALibrary.CHANNEL_ACCESS_JAVA);

        WAIT_TIMEOUT = config.getAttributeAsInteger("pool_timeout", 1000);
        MAX_SIZE = config.getAttributeAsInteger("pool_max", 2);
        
        for (int i = 0; i < MAX_SIZE; i++) {
            addContext();
        }
    }

    /**
     * Determine if a context is still viable.
     * 
     * @param context the context.
     * @return true if the context is viable.
     */
    private synchronized boolean isViable(CAJContext context) {
        return (context != null && context.isInitialized() && !context.isDestroyed());
    }
    
    /**
     * Add a new context to the pool.
     * 
     * @throws CAException if unable to add the context.
     */
    private synchronized void addContext() throws CAException {
        CAJContext context = (CAJContext) JCALibrary.getInstance().createContext(config);

        context.initialize();

        available.add(context);
    }

    /**
     * Replace a non-viable context with a new one.
     * 
     * @return a new context.
     * @throws CAException if unable to replace the context.
     */
    private synchronized CAJContext replaceContext() throws CAException {
        logger.log(Level.WARNING, "Non-viable context encountered: context is null, uninitialized, or destroyed");
            
        addContext();
        
        CAJContext context = available.remove(available.size() - 1);
        
        if(!isViable(context)) {
            throw new CAException("Unable to construct a viable context");
        }
        
        return context;
    }
    
    /**
     * Clean up the context pool and release EPICS resources.
     * 
     * This method should be called when you are done with the pool.
     * 
     * @throws CAException if unable to clean up the context pool.
     */
    public synchronized void destroy() throws CAException {
        logger.log(Level.FINEST, "Destroying EPICS ContextPool");
        boolean exception = false;

        for (CAJContext context : available) {
            try {
                context.destroy();
            } catch (CAException e) {
                exception = true;
            }
        }

        available.clear();
        available = null;

        for (CAJContext context : used) {
            try {
                context.destroy();
            } catch (CAException e) {
                exception = true;
            }
        }

        used.clear();
        used = null;

        if (exception) {
            throw new CAException("Unable to destroy context pool: Unable to destroy all contexts");
        }
    }

    /**
     * Get a EPICS channel access context from the context pool.
     * 
     * @return an EPICS channel access context.
     * @throws CAException if unable to obtain a context.
     */
    public synchronized CAJContext getContext() throws CAException {
        logger.log(Level.FINEST, "ContextPool.getContext");

        if (available.isEmpty()) {
            logger.log(Level.FINEST, "Waiting for context");
            try {
                this.wait(WAIT_TIMEOUT);
            } catch (InterruptedException ex) {
                throw new CAException("Unable to get a context from context pool: thread interrupted");
            }
        }

        if (available.isEmpty()) {
            throw new CAException("Unable to get a context from context pool: timeout reached");
        }

        CAJContext context = available.remove(0);

        if (!isViable(context)) {
            context = replaceContext();
        }

        used.add(context);

        return context;
    }

    /**
     * Return a context to the pool.
     * 
     * @param context the context.
     * @throws CAException if unable to return the context. 
     */
    public synchronized void returnContext(CAJContext context) throws CAException {
        logger.log(Level.FINEST, "ContextPool.returnContext");
        boolean success;

        success = used.remove(context);

        if (!success) {
            throw new CAException("Unable to return a context from context pool: context does not exist in used set");
        }

        if (!isViable(context)) {
            context = replaceContext();
        }

        available.add(context);

        this.notify();
    }
}
