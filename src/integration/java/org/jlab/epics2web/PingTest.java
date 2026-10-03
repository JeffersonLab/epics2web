package org.jlab.epics2web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
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
    try (Socket socket = new Socket("localhost", 8080)) {
      handshake(socket);
      sendText(socket.getOutputStream(), "{\"type\": \"monitor\",\"pvs\": [\"channel1\"]}");
      long start = System.currentTimeMillis();
      long deadline = start + TIMEOUT_MILLIS + PING_INTERVAL_MILLIS * 3;

      // Reading lets the test see the close; the server can't tell, since no pong is sent.
      InputStream in = socket.getInputStream();
      int opcode;
      int pings = 0;
      do {
        int timeout = (int) (deadline - System.currentTimeMillis());
        if (timeout <= 0) {
          fail("Server didn't close a client that doesn't answer pings");
        }
        socket.setSoTimeout(timeout);
        try {
          opcode = readFrame(in);
        } catch (SocketTimeoutException e) {
          fail("Server didn't close a client that doesn't answer pings");
          return;
        }
        if (opcode == 0x9) {
          pings++;
        }
      } while (opcode != 0x8);

      long elapsed = System.currentTimeMillis() - start;
      assertTrue("Closed after only " + elapsed + " ms", elapsed >= TIMEOUT_MILLIS - 1_000);
      assertTrue("Closed without being pinged", pings > 0);
    }
  }

  /** Reads one unmasked frame from the server, discarding its payload, and returns its opcode. */
  private static int readFrame(InputStream in) throws IOException {
    int first = readByte(in);
    long length = readByte(in) & 0x7F;
    int lengthBytes = length == 126 ? 2 : length == 127 ? 8 : 0;
    if (lengthBytes > 0) {
      length = 0;
      for (int i = 0; i < lengthBytes; i++) {
        length = (length << 8) | readByte(in);
      }
    }
    for (long i = 0; i < length; i++) {
      readByte(in);
    }
    return first & 0x0F;
  }

  private static int readByte(InputStream in) throws IOException {
    int b = in.read();
    if (b == -1) {
      throw new IOException("Connection closed without a close frame");
    }
    return b;
  }

  private static void handshake(Socket socket) throws IOException {
    byte[] nonce = new byte[16];
    new SecureRandom().nextBytes(nonce);
    String request =
        "GET /epics2web/monitor HTTP/1.1\r\n"
            + "Host: localhost:8080\r\n"
            + "Upgrade: websocket\r\n"
            + "Connection: Upgrade\r\n"
            + "Sec-WebSocket-Key: "
            + Base64.getEncoder().encodeToString(nonce)
            + "\r\n"
            + "Sec-WebSocket-Version: 13\r\n\r\n";
    socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));

    // Read the response headers, up to the blank line
    InputStream in = socket.getInputStream();
    ByteArrayOutputStream headers = new ByteArrayOutputStream();
    while (!headers.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n")) {
      int b = in.read();
      if (b == -1) {
        fail("Connection closed during handshake: " + headers);
      }
      headers.write(b);
    }
    String status = headers.toString(StandardCharsets.US_ASCII).split("\r\n")[0];
    assertEquals("HTTP/1.1 101 ", status.substring(0, Math.min(13, status.length())));
  }

  /** Sends a short, masked text frame, as a client must. */
  private static void sendText(OutputStream out, String text) throws IOException {
    byte[] payload = text.getBytes(StandardCharsets.UTF_8);
    if (payload.length > 125) {
      throw new IllegalArgumentException("Only short frames are supported");
    }
    byte[] mask = new byte[4];
    new SecureRandom().nextBytes(mask);

    out.write(0x81); // Final frame, text
    out.write(0x80 | payload.length); // Masked
    out.write(mask);
    for (int i = 0; i < payload.length; i++) {
      out.write(payload[i] ^ mask[i % 4]);
    }
    out.flush();
  }
}
