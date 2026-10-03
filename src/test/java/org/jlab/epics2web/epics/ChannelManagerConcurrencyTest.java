package org.jlab.epics2web.epics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.cosylab.epics.caj.CAJContext;
import gov.aps.jca.JCALibrary;
import gov.aps.jca.configuration.DefaultConfiguration;
import gov.aps.jca.dbr.DBR;
import gov.aps.jca.dbr.DBRType;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Races ChannelManager.addPv and removePv against each other. No IOC is needed: channels to
 * nonexistent PVs stay in the connecting state, which is enough to exercise the bookkeeping.
 */
public class ChannelManagerConcurrencyTest {

  private static final int ITERATIONS = 500;
  private static final int CONCURRENT_CLIENTS = 8;

  private CAJContext context;
  private ScheduledExecutorService timeoutExecutor;
  private ExecutorService callbackExecutor;
  private ExecutorService workers;
  private ChannelManager manager;

  /**
   * CAJ otherwise starts a CA repeater in a separate JVM that outlives the tests and holds UDP
   * 5065, which the test IOC needs. CAJ checks only that the property exists, not its value.
   */
  @BeforeClass
  public static void disableRepeater() {
    System.setProperty("CA_DISABLE_REPEATER", "true");
  }

  @Before
  public void setUp() throws Exception {
    DefaultConfiguration config = new DefaultConfiguration("test");
    config.setAttribute("class", JCALibrary.CHANNEL_ACCESS_JAVA);
    config.setAttribute("addr_list", "127.0.0.1");
    config.setAttribute("auto_addr_list", "false");
    context = (CAJContext) JCALibrary.getInstance().createContext(config);
    timeoutExecutor = Executors.newSingleThreadScheduledExecutor();
    callbackExecutor = Executors.newCachedThreadPool();
    workers = Executors.newCachedThreadPool();
    manager = new ChannelManager(context, timeoutExecutor, callbackExecutor);
  }

  @After
  public void tearDown() throws Exception {
    workers.shutdownNow();
    timeoutExecutor.shutdownNow();
    callbackExecutor.shutdownNow();
    context.destroy();
  }

  /** Clients that subscribe to a new PV at the same moment must all share one monitor. */
  @Test(timeout = 120_000)
  public void concurrentAddsShareOneMonitor() throws Exception {
    for (int i = 0; i < ITERATIONS; i++) {
      String pv = "epics2web:test:add:" + i;
      List<PvListener> listeners = new ArrayList<>();
      List<Callable<Void>> tasks = new ArrayList<>();

      for (int c = 0; c < CONCURRENT_CLIENTS; c++) {
        PvListener listener = new NoopListener();
        listeners.add(listener);
        tasks.add(
            () -> {
              manager.addPv(listener, pv);
              return null;
            });
      }

      runConcurrently(tasks);

      ChannelMonitor monitor = manager.getMonitorMap().get(pv);
      assertNotNull("No monitor for " + pv, monitor);
      assertEquals(
          "Listeners missing from the shared monitor for " + pv,
          CONCURRENT_CLIENTS,
          monitor.getListenerCount());

      for (PvListener listener : listeners) {
        manager.removeAll(listener);
      }
      assertEquals(0, manager.getMonitorMap().size());
    }
  }

  /** A client joining while the last client leaves must end up on a live, mapped monitor. */
  @Test(timeout = 120_000)
  public void addRacingRemoveOfLastListenerKeepsMonitor() throws Exception {
    for (int i = 0; i < ITERATIONS; i++) {
      String pv = "epics2web:test:swap:" + i;
      PvListener leaving = new NoopListener();
      PvListener joining = new NoopListener();

      manager.addPv(leaving, pv);

      List<Callable<Void>> tasks = new ArrayList<>();
      tasks.add(
          () -> {
            manager.removePv(leaving, pv);
            return null;
          });
      tasks.add(
          () -> {
            manager.addPv(joining, pv);
            return null;
          });

      runConcurrently(tasks);

      ChannelMonitor monitor = manager.getMonitorMap().get(pv);
      assertNotNull("Joining client left on an unmapped (closed) monitor for " + pv, monitor);
      assertEquals(1, monitor.getListenerCount());

      manager.removeAll(joining);
      assertEquals(0, manager.getMonitorMap().size());
    }
  }

  private void runConcurrently(List<Callable<Void>> tasks) throws Exception {
    CyclicBarrier barrier = new CyclicBarrier(tasks.size());
    List<Future<Void>> futures = new ArrayList<>();

    for (Callable<Void> task : tasks) {
      futures.add(
          workers.submit(
              () -> {
                barrier.await();
                return task.call();
              }));
    }

    for (Future<Void> future : futures) {
      future.get(10, TimeUnit.SECONDS);
    }
  }

  private static class NoopListener implements PvListener {
    @Override
    public void notifyPvInfo(
        String pv, boolean couldConnect, DBRType type, Integer count, String[] enumLabels) {}

    @Override
    public void notifyPvUpdate(String pv, DBR dbr) {}
  }
}
