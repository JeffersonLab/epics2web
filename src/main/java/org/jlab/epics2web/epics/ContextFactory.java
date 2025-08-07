package org.jlab.epics2web.epics;

import com.cosylab.epics.caj.CAJContext;
import gov.aps.jca.CAException;
import gov.aps.jca.JCALibrary;
import gov.aps.jca.configuration.DefaultConfiguration;
import java.util.logging.Logger;

/**
 * This class is a service locator / factory for obtaining EPICS contexts.
 *
 * <p>This class wraps around a ContextPool and provides life cycle management.
 *
 * <p>A resource bundle named epics.properties is consulted to determine the EPICS addr_list value.
 *
 * <p>This class is a singleton which is created on application startup and destroyed on application
 * shutdown.
 *
 * @author slominskir
 */
public class ContextFactory {

  private static final Logger logger = Logger.getLogger(ContextFactory.class.getName());
  private final DefaultConfiguration config;

  public ContextFactory() {
    this(getDefault());
  }

  public ContextFactory(DefaultConfiguration config) {
    this.config = config;
  }

  /**
   * Get an EPICS channel access context. Make sure to destroy it when you're done.
   *
   * @return the context.
   * @throws CAException if unable to get the context.
   */
  public CAJContext newContext() throws CAException {
    return (CAJContext) JCALibrary.getInstance().createContext(config);
  }

  /** Get default CA configuration, which looks for the environment variable EPICS_CA_ADDR_LIST. */
  public static DefaultConfiguration getDefault() {
    DefaultConfiguration defaultConfig = new DefaultConfiguration("myconfig");

    String addrList = System.getenv("EPICS_CA_ADDR_LIST");

    defaultConfig.setAttribute("addr_list", addrList);
    defaultConfig.setAttribute("auto_addr_list", "false");
    defaultConfig.setAttribute("class", JCALibrary.CHANNEL_ACCESS_JAVA);

    return defaultConfig;
  }
}
