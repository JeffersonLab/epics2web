package org.jlab.epics2web;

import static org.junit.Assert.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.util.regex.Pattern;
import org.junit.Test;

/** Tests the /console page. */
public class ConsoleTest {

  private static final HttpClient HTTP = HttpClient.newHttpClient();

  /**
   * A request without Accept-Language, such as from a script, gets the console's time format, not
   * Date.toString(), which JSTL falls back to when it finds no locale. HttpClient sends no
   * Accept-Language.
   */
  @Test
  public void timesAreFormattedWithoutAcceptLanguage() throws Exception {
    WebSocket socket =
        HTTP.newWebSocketBuilder()
            .buildAsync(
                URI.create("ws://localhost:8080/epics2web/monitor?clientName=console-test"),
                new WebSocket.Listener() {})
            .join();

    try {
      socket.sendText("{\"type\": \"monitor\", \"pvs\": [\"channel1\"]}", true).join();

      // Monitor row cells: PV, state, last update ("MMM dd yyyy HH:mm:ss - value")
      Pattern formatted =
          Pattern.compile(
              "<td>channel1</td>\\s*<td>[^<]*</td>\\s*<td>[A-Z][a-z]{2} \\d{2} \\d{4}"
                  + " \\d{2}:\\d{2}:\\d{2} - ");

      long deadline = System.currentTimeMillis() + 5_000;
      boolean found = false;

      while (!found && System.currentTimeMillis() < deadline) {
        found = formatted.matcher(get("console").body()).find();
        if (!found) {
          Thread.sleep(100);
        }
      }

      assertTrue("channel1's last update not formatted on the console", found);
    } finally {
      socket.abort();
    }
  }

  private static HttpResponse<String> get(String path) throws Exception {
    return HTTP.send(
        HttpRequest.newBuilder().uri(URI.create("http://localhost:8080/epics2web/" + path)).build(),
        HttpResponse.BodyHandlers.ofString());
  }
}
