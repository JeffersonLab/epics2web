package org.jlab.epics2web;

import static org.junit.Assert.assertEquals;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * A minimal WebSocket client on a plain socket, for tests that need what the JDK client won't do:
 * leave pings unanswered, or stop reading.
 */
class RawWebSocket implements AutoCloseable {

  static final int OPCODE_CLOSE = 0x8;
  static final int OPCODE_PING = 0x9;

  private static final SecureRandom RANDOM = new SecureRandom();

  final Socket socket;
  private final InputStream in;
  private final OutputStream out;

  /**
   * Connect to the monitor endpoint.
   *
   * @param query The query string, such as "clientName=test", or null
   * @param receiveBufferSize The socket's receive buffer size, or 0 for the default
   */
  RawWebSocket(String query, int receiveBufferSize) throws IOException {
    socket = new Socket();
    if (receiveBufferSize > 0) {
      socket.setReceiveBufferSize(receiveBufferSize); // Before connecting, so it limits the window
    }
    socket.connect(new InetSocketAddress("localhost", 8080));
    in = new BufferedInputStream(socket.getInputStream());
    out = socket.getOutputStream();
    handshake(query == null ? "" : "?" + query);
  }

  RawWebSocket() throws IOException {
    this(null, 0);
  }

  private void handshake(String query) throws IOException {
    byte[] nonce = new byte[16];
    RANDOM.nextBytes(nonce);
    String request =
        "GET /epics2web/monitor"
            + query
            + " HTTP/1.1\r\n"
            + "Host: localhost:8080\r\n"
            + "Upgrade: websocket\r\n"
            + "Connection: Upgrade\r\n"
            + "Sec-WebSocket-Key: "
            + Base64.getEncoder().encodeToString(nonce)
            + "\r\n"
            + "Sec-WebSocket-Version: 13\r\n\r\n";
    out.write(request.getBytes(StandardCharsets.US_ASCII));

    // Read the response headers, up to the blank line
    ByteArrayOutputStream headers = new ByteArrayOutputStream();
    while (!headers.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n")) {
      headers.write(readByte());
    }
    String status = headers.toString(StandardCharsets.US_ASCII).split("\r\n")[0];
    assertEquals("HTTP/1.1 101 ", status.substring(0, Math.min(13, status.length())));
  }

  /** Sends a masked text frame, as a client must. */
  void sendText(String text) throws IOException {
    byte[] payload = text.getBytes(StandardCharsets.UTF_8);
    if (payload.length > 0xFFFF) {
      throw new IllegalArgumentException("Frames over 64 KB aren't supported");
    }
    byte[] mask = new byte[4];
    RANDOM.nextBytes(mask);

    ByteArrayOutputStream frame = new ByteArrayOutputStream();
    frame.write(0x81); // Final frame, text
    if (payload.length < 126) {
      frame.write(0x80 | payload.length); // Masked
    } else {
      frame.write(0x80 | 126); // Masked, 16 bit length follows
      frame.write(payload.length >> 8);
      frame.write(payload.length & 0xFF);
    }
    frame.write(mask);
    for (int i = 0; i < payload.length; i++) {
      frame.write(payload[i] ^ mask[i % 4]);
    }
    out.write(frame.toByteArray());
    out.flush();
  }

  /**
   * Reads one unmasked frame from the server, discarding its payload.
   *
   * @return The frame's opcode
   * @throws EOFException If the connection ends
   */
  int readFrame() throws IOException {
    int first = readByte();
    long length = readByte() & 0x7F;
    int lengthBytes = length == 126 ? 2 : length == 127 ? 8 : 0;
    if (lengthBytes > 0) {
      length = 0;
      for (int i = 0; i < lengthBytes; i++) {
        length = (length << 8) | readByte();
      }
    }
    in.skipNBytes(length);
    return first & 0x0F;
  }

  private int readByte() throws IOException {
    int b = in.read();
    if (b == -1) {
      throw new EOFException("Connection closed");
    }
    return b;
  }

  @Override
  public void close() throws IOException {
    socket.close();
  }
}
