package org.jlab.epics2web;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

/**
 * Tests that the server pings WebSocket clients and closes those that stop answering. Needs the
 * short intervals build.yaml sets: a ping every 2 seconds, and a timeout of 5 seconds.
 */
public class PingTest {

  private static final long PING_INTERVAL_MILLIS = 2_000;
  private static final long TIMEOUT_MILLIS = 5_000;

  @Rule public Timeout globalTimeout = Timeout.seconds(30);

  /** The JDK client answers pings automatically, like a browser, and sends nothing else. */
  @Test
  public void clientThatAnswersPingsStaysConnected() throws Exception {
    AtomicInteger pings = new AtomicInteger();
    AtomicBoolean closed = new AtomicBoolean();

    WebSocket socket =
        HttpClient.newHttpClient()
            .newWebSocketBuilder()
            .buildAsync(
                URI.create("ws://localhost:8080/epics2web/monitor"),
                new WebSocket.Listener() {
                  @Override
                  public CompletionStage<?> onPing(WebSocket ws, ByteBuffer message) {
                    pings.incrementAndGet();
                    return WebSocket.Listener.super.onPing(ws, message);
                  }

                  @Override
                  public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
                    closed.set(true);
                    return null;
                  }
                })
            .join();

    try {
      socket.sendText("{\"type\": \"monitor\",\"pvs\": [\"channel1\"]}", true).join();

      Thread.sleep(TIMEOUT_MILLIS * 2 + PING_INTERVAL_MILLIS); // More than twice the timeout

      assertFalse("Server closed a client that answers pings", closed.get());
      assertFalse(socket.isInputClosed());
      assertTrue("Only " + pings.get() + " pings received", pings.get() >= 4);
    } finally {
      socket.abort();
    }
  }

  /**
   * A raw client that never answers pings must be sent a close frame once the timeout passes.
   * Tomcat then waits for the client's own close frame, up to its SESSION_CLOSE_TIMEOUT (30 s by
   * default), before it drops the connection, so the test checks for the close frame.
   */
  @Test
  public void clientThatDoesNotAnswerPingsIsClosed() throws Exception {
    try (RawWebSocket client = new RawWebSocket()) {
      client.sendText("{\"type\": \"monitor\",\"pvs\": [\"channel1\"]}");
      long start = System.currentTimeMillis();
      long deadline = start + TIMEOUT_MILLIS + PING_INTERVAL_MILLIS * 3;

      // Reading lets the test see the close; the server can't tell, since no pong is sent.
      int opcode;
      int pings = 0;
      do {
        int timeout = (int) (deadline - System.currentTimeMillis());
        if (timeout <= 0) {
          fail("Server didn't close a client that doesn't answer pings");
        }
        client.socket.setSoTimeout(timeout);
        try {
          opcode = client.readFrame();
        } catch (SocketTimeoutException e) {
          fail("Server didn't close a client that doesn't answer pings");
          return;
        }
        if (opcode == RawWebSocket.OPCODE_PING) {
          pings++;
        }
      } while (opcode != RawWebSocket.OPCODE_CLOSE);

      long elapsed = System.currentTimeMillis() - start;
      assertTrue("Closed after only " + elapsed + " ms", elapsed >= TIMEOUT_MILLIS - 1_000);
      assertTrue("Closed without being pinged", pings > 0);
    }
  }
}
