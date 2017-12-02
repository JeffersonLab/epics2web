package org.jlab.epics2web.epics;

import com.cosylab.epics.caj.CAJContext;
import gov.aps.jca.CAException;
import gov.aps.jca.JCALibrary;
import gov.aps.jca.configuration.DefaultConfiguration;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * This class is a service locator / factory for obtaining EPICS contexts.
 * 
 * This class wraps around a ContextPool and provides life cycle management.
 * 
 * <p>A resource bundle named epics.properties is consulted to determine
 * the EPICS addr_list value.</p>
 * 
 * <p>This class is a singleton which is created on application startup and 
 * destroyed on application shutdown.</p>
 * 
 * @author ryans
 */
public class ContextFactory {
    
    private static final Logger logger = Logger.getLogger(ContextFactory.class.getName());
    private final DefaultConfiguration config = new DefaultConfiguration("myconfig");
    
    public ContextFactory() {
        construct();
    }
    
    /**
     * Get an EPICS channel access context.  Make sure to destroy it when you're done.
     * 
     * @return the context.
     * @throws CAException if unable to get the context.
     */
    public CAJContext newContext() throws CAException {
        return (CAJContext) JCALibrary.getInstance().createContext(config);
    }
    
    /**
     * Construct the context factory.
     */
    private void construct() {
        logger.log(Level.FINEST, "Constructing ContextFactory");

        String addrList = System.getenv("EPICS_CA_ADDR_LIST");
        
        if(addrList == null || addrList.trim().isEmpty()) {
            throw new RuntimeException("Environment variable EPICS_CA_ADDR_LIST must be set");
        }
        
        config.setAttribute("addr_list", addrList);
        config.setAttribute("auto_addr_list", "false");
        config.setAttribute("class", JCALibrary.CHANNEL_ACCESS_JAVA);       
    }  
}
