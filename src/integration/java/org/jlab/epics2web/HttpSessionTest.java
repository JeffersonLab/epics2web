package org.jlab.epics2web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/**
 * Tests that only WebSocket handshakes create HTTP sessions (#44): a session per request kept
 * clients without cookies, such as scripts, from being cheap to serve.
 */
public class HttpSessionTest {

  private static final HttpClient HTTP = HttpClient.newHttpClient();

  @Test
  public void requestsDoNotCreateSessions() throws Exception {
    for (String path :
        List.of(
            "caget?pv=channel1",
            "healthcheck",
            "console",
            "test-camonitor",
            "resources/js/epics2web.js")) {
      HttpResponse<String> response = get(path);

      assertEquals(path, 200, response.statusCode());
      assertEquals(path + " set a cookie", List.of(), response.headers().allValues("Set-Cookie"));
    }
  }

  /** The handshake's session carries the client's address to the endpoint, for the console. */
  @Test
  public void consoleShowsWebSocketClientAddress() throws Exception {
    String name = "session-test-" + UUID.randomUUID();
    WebSocket socket =
        HTTP.newWebSocketBuilder()
            .buildAsync(
                URI.create("ws://localhost:8080/epics2web/monitor?clientName=" + name),
                new WebSocket.Listener() {})
            .join();

    try {
      String address = consoleAddressOf(name);
      assertNotNull("Client not on the console", address);
      assertNotEquals("Client address unknown", "Unknown", address);
    } finally {
      socket.abort();
    }
  }

  /**
   * Waits for the console to list the client, and returns its address, or null if it never does.
   */
  private static String consoleAddressOf(String name) throws Exception {
    // Row cells: ID, IP, agent, name. The server may list the client just after the handshake.
    Pattern row =
        Pattern.compile("<td>[^<]*</td>\\s*<td>([^<]*)</td>\\s*<td>[^<]*</td>\\s*<td>" + name);
    long deadline = System.currentTimeMillis() + 5_000;
    while (true) {
      Matcher matcher = row.matcher(get("console").body());
      if (matcher.find()) {
        return matcher.group(1);
      }
      if (System.currentTimeMillis() > deadline) {
        return null;
      }
      Thread.sleep(100);
    }
  }

  private static HttpResponse<String> get(String path) throws Exception {
    return HTTP.send(
        HttpRequest.newBuilder().uri(URI.create("http://localhost:8080/epics2web/" + path)).build(),
        HttpResponse.BodyHandlers.ofString());
  }
}
