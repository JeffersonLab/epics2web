package org.jlab.epics2web.websocket;

import static org.junit.Assert.assertEquals;
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

  @Test
  public void clientNameIsReadAmongOtherParameters() {
    assertEquals("screen", MonitorEndpoint.clientName("clientName=screen"));
    assertEquals("screen", MonitorEndpoint.clientName("a=1&clientName=screen"));
    assertEquals("screen", MonitorEndpoint.clientName("clientName=screen&a=1&b"));
    assertEquals("screen", MonitorEndpoint.clientName("a&clientName=screen"));
  }

  /** The JavaScript client sends its page's address, which may have a query string of its own. */
  @Test
  public void clientNameIsDecoded() {
    assertEquals(
        "https://example.org/wedm/screen?edl=a b.edl&x=1",
        MonitorEndpoint.clientName(
            "clientName=https%3A%2F%2Fexample.org%2Fwedm%2Fscreen%3Fedl%3Da%20b.edl%26x%3D1"));
  }

  @Test
  public void clientNameIsEmptyWhenMissingOrMalformed() {
    assertEquals("", MonitorEndpoint.clientName(null));
    assertEquals("", MonitorEndpoint.clientName(""));
    assertEquals("", MonitorEndpoint.clientName("a=1"));
    assertEquals("", MonitorEndpoint.clientName("xclientName=screen"));
    assertEquals("", MonitorEndpoint.clientName("clientName"));
    assertEquals("", MonitorEndpoint.clientName("clientName="));
    assertEquals("", MonitorEndpoint.clientName("clientName=%zz"));
  }
}
