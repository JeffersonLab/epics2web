package org.jlab.epics2web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.cosylab.epics.caj.CAJContext;
import gov.aps.jca.JCALibrary;
import gov.aps.jca.Monitor;
import gov.aps.jca.configuration.DefaultConfiguration;
import gov.aps.jca.dbr.DBR;
import gov.aps.jca.dbr.DBRType;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.jlab.epics2web.epics.CaProbeFactory;
import org.jlab.epics2web.epics.ChannelManager;
import org.jlab.epics2web.epics.ChannelMonitor;
import org.jlab.epics2web.epics.FrozenPvDetector;
import org.jlab.epics2web.epics.PvListener;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

/**
 * Runs FrozenPvDetector in this JVM against the test IOC, through its published ports, with two
 * real CA contexts. A lost subscription is simulated by clearing a monitor's CAJ subscription
 * without telling the monitor, as a CA client bug might.
 */
public class FrozenPvDetectionTest {

  private static final String MISSING = "epics2web:test:frozen:missing";

  @Rule public Timeout globalTimeout = Timeout.seconds(90);

  private CAJContext monitorContext;
  private CAJContext probeContext;
  private ScheduledExecutorService timeoutExecutor;
  private ExecutorService callbackExecutor;
  private ChannelManager manager;
  private FrozenPvDetector detector;
  private final PvListener listener = new NoopListener();

  @BeforeClass
  public static void disableRepeater() {
    System.setProperty("CA_DISABLE_REPEATER", "true"); // As in the unit tests (#32)
  }

  @Before
  public void setUp() throws Exception {
    monitorContext = newContext();
    probeContext = newContext();
    timeoutExecutor = Executors.newSingleThreadScheduledExecutor();
    callbackExecutor = Executors.newCachedThreadPool();
    manager = new ChannelManager(monitorContext, timeoutExecutor, callbackExecutor);
    detector =
        new FrozenPvDetector(
            () -> FrozenPvDetector.viewsOf(manager.getMonitorMap()),
            new CaProbeFactory(probeContext),
            Clock.systemUTC(),
            Duration.ofSeconds(2),
            Duration.ofSeconds(1),
            20);
  }

  @After
  public void tearDown() throws Exception {
    detector.close();
    manager.removeAll(listener);
    timeoutExecutor.shutdownNow();
    callbackExecutor.shutdownNow();
    monitorContext.destroy();
    probeContext.destroy();
  }

  @Test
  public void lostSubscriptionIsFrozenAndNothingElseIs() throws Exception {
    manager.addPv(listener, "HELLO"); // Changes every 0.2 s
    manager.addPv(listener, "channel1"); // Never changes
    manager.addPv(listener, MISSING); // Never connects

    // Working monitors, a PV that doesn't change, and a PV that doesn't exist aren't frozen. 10 s
    // is longer than the 6 s after which a PV counts as quiet, and the 2 s grace period.
    checkFor(10);
    assertEquals(Map.of(), detector.getFrozen());
    assertTrue(manager.getMonitorMap().get("HELLO").getUpdateCount() > 10);

    loseSubscription(manager.getMonitorMap().get("HELLO"));

    long deadline = System.currentTimeMillis() + 30_000;
    while (!detector.getFrozen().containsKey("HELLO") && System.currentTimeMillis() < deadline) {
      checkFor(1);
    }
    assertEquals(Set.of("HELLO"), detector.getFrozen().keySet());
    assertTrue(
        detector.getFrozen().get("HELLO").reason(),
        detector.getFrozen().get("HELLO").reason().contains("didn't receive"));
  }

  private void checkFor(int seconds) throws InterruptedException {
    for (int i = 0; i < seconds; i++) {
      Thread.sleep(1_000);
      detector.check();
    }
  }

  /** Cancel the monitor's CAJ subscription without telling the monitor. */
  private static void loseSubscription(ChannelMonitor monitor) throws Exception {
    Field field = ChannelMonitor.class.getDeclaredField("monitor");
    field.setAccessible(true);
    ((Monitor) field.get(monitor)).clear();
  }

  private static CAJContext newContext() throws Exception {
    DefaultConfiguration config = new DefaultConfiguration("test");
    config.setAttribute("class", JCALibrary.CHANNEL_ACCESS_JAVA);
    config.setAttribute("addr_list", "127.0.0.1");
    config.setAttribute("auto_addr_list", "false");
    return (CAJContext) JCALibrary.getInstance().createContext(config);
  }

  private static class NoopListener implements PvListener {
    @Override
    public void notifyPvInfo(
        String pv, boolean couldConnect, DBRType type, Integer count, String[] enumLabels) {}

    @Override
    public void notifyPvUpdate(String pv, DBR dbr) {}
  }
}
