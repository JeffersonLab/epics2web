package org.jlab.epics2web;

import static org.junit.Assert.assertTrue;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.Test;

/** Tests that clients are named by the clientName parameter of the WebSocket handshake. */
public class ClientNameTest {

  private static final HttpClient HTTP = HttpClient.newHttpClient();

  /** The name is read even when the query has other parameters, and may contain = and &. */
  @Test
  public void consoleShowsClientNameAmongOtherParameters() throws Exception {
    String name = "name-test-" + UUID.randomUUID() + "?a=1&b=2";
    WebSocket socket =
        HTTP.newWebSocketBuilder()
            .buildAsync(
                URI.create(
                    "ws://localhost:8080/epics2web/monitor?other=1&clientName="
                        + URLEncoder.encode(name, StandardCharsets.UTF_8)
                        + "&another=2"),
                new WebSocket.Listener() {})
            .join();

    try {
      assertTrue("Client name not on the console", consoleShows(name.replace("&", "&amp;")));
    } finally {
      socket.abort();
    }
  }

  /** Waits for the console to show the text; the server may list the client after the handshake. */
  private static boolean consoleShows(String text) throws Exception {
    long deadline = System.currentTimeMillis() + 5_000;
    while (true) {
      if (get("console").body().contains("<td>" + text + "</td>")) {
        return true;
      }
      if (System.currentTimeMillis() > deadline) {
        return false;
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
