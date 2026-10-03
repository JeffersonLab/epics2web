package org.jlab.epics2web.websocket;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import jakarta.websocket.CloseReason;
import java.time.Duration;
import org.jlab.epics2web.Application;
import org.jlab.epics2web.epics.ChannelManager;
import org.jlab.epics2web.epics.PvListener;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class MonitorEndpointTest {

  private WebSocketSessionManager previousManager;
  private WebSocketSessionManager manager;

  @Before
  public void setUp() {
    previousManager = Application.sessionManager;
    manager =
        new WebSocketSessionManager(
            new ChannelManager(null, null, null) {
              @Override
              public void removeAll(PvListener listener) {}
            },
            Duration.ofSeconds(60));
    Application.sessionManager = manager;
  }

  @After
  public void tearDown() {
    Application.sessionManager = previousManager;
    Thread.interrupted(); // Don't leave a failed test's interrupt for the next test
  }

  /**
   * onClose may run on the session's writer thread, when Tomcat closes the session after a failed
   * write. It must stop the writer without interrupting the thread it runs on: an interrupted
   * thread can't destroy the session's Channel Access channels (#42).
   */
  @Test
  public void onCloseStopsWriterWithoutInterrupting() throws Exception {
    FakeSession client = new FakeSession("1");
    manager.addClient(client.session);
    client.writeQueue.offer("waiting");

    new MonitorEndpoint()
        .onClose(client.session, new CloseReason(CloseReason.CloseCodes.NORMAL_CLOSURE, null));

    assertFalse("onClose interrupted its thread", Thread.currentThread().isInterrupted());
    assertNull("Write queue not closed", client.writeQueue.take());
    assertTrue(manager.toSet().isEmpty());
  }
}
