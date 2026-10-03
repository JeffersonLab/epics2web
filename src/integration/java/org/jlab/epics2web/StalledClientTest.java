package org.jlab.epics2web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.EOFException;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntPredicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

/**
 * Tests that a client that stops reading is closed, and that other clients keep getting updates
 * meanwhile (#4). Needs build.yaml's 3 second send timeout and the test IOC's big_string records,
 * which fill the stalled client's TCP buffers in seconds.
 */
public class StalledClientTest {

  private static final HttpClient HTTP = HttpClient.newHttpClient();

  @Rule public Timeout globalTimeout = Timeout.seconds(60);

  @Test
  public void stalledClientIsClosedWhileOthersKeepUpdating() throws Exception {
    AtomicInteger healthyUpdates = new AtomicInteger();
    AtomicBoolean healthyClosed = new AtomicBoolean();
    WebSocket healthy =
        HTTP.newWebSocketBuilder()
            .buildAsync(
                URI.create("ws://localhost:8080/epics2web/monitor"),
                new WebSocket.Listener() {
                  @Override
                  public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                    if (data.toString().contains("\"update\"")) {
                      healthyUpdates.incrementAndGet();
                    }
                    return WebSocket.Listener.super.onText(ws, data, last);
                  }

                  @Override
                  public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
                    healthyClosed.set(true);
                    return null;
                  }
                })
            .join();

    String name = "stalled-" + UUID.randomUUID();
    String pvs =
        IntStream.rangeClosed(1, 8)
            .mapToObj(i -> "\"big_string_" + i + ".VAL$\"")
            .collect(Collectors.joining(","));

    healthy.sendText("{\"type\": \"monitor\",\"pvs\": [\"HELLO\"]}", true).join();
    while (healthyUpdates.get() == 0) {
      Thread.sleep(100);
    }
    int channelsBefore = channelCount();

    try (RawWebSocket stalled = new RawWebSocket("clientName=" + name, 4096)) {
      stalled.sendText("{\"type\": \"monitor\",\"pvs\": [" + pvs + "]}");

      // The stalled client never reads, so the server's writes to it block once the TCP buffers
      // fill. It keeps sending pings, so only the blocked writes can get it closed, not the
      // timeout for clients that send nothing.
      assertTrue("Stalled client never listed", waitForListed(name, true, 5_000, stalled));
      assertTrue(
          "Stalled client's channels never opened",
          waitForChannelCount(count -> count > channelsBefore));
      assertTrue("Stalled client not closed", waitForListed(name, false, 30_000, stalled));

      int before = healthyUpdates.get();
      Thread.sleep(1_000);
      assertTrue("Other client stopped getting updates", healthyUpdates.get() > before);
      assertFalse("Other client was closed", healthyClosed.get());

      // Its channels must be destroyed, not left open (#42)
      waitForChannelCount(count -> count == channelsBefore);
      assertEquals("Channel Access channels left open", channelsBefore, channelCount());

      // The server closed the connection: once read, the buffered data ends
      stalled.socket.setSoTimeout(15_000);
      try {
        while (true) {
          stalled.readFrame();
        }
      } catch (EOFException e) {
        // Expected
      } catch (SocketTimeoutException e) {
        fail("Connection to the stalled client is still open");
      }
    } finally {
      healthy.abort();
    }
  }

  /** Waits until the console does or doesn't list a client, sending pings meanwhile. */
  private static boolean waitForListed(
      String name, boolean listed, long timeoutMillis, RawWebSocket client)
      throws IOException, InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMillis;
    while (System.currentTimeMillis() < deadline) {
      if (consoleLists(name) == listed) {
        return true;
      }
      try {
        client.sendText("{\"type\": \"ping\"}");
      } catch (IOException e) {
        // The server may have closed the connection already
      }
      Thread.sleep(500);
    }
    return false;
  }

  private static boolean consoleLists(String name) throws IOException, InterruptedException {
    return console().contains("<td>" + name + "</td>");
  }

  private static boolean waitForChannelCount(IntPredicate condition)
      throws IOException, InterruptedException {
    long deadline = System.currentTimeMillis() + 5_000;
    while (System.currentTimeMillis() < deadline) {
      if (condition.test(channelCount())) {
        return true;
      }
      Thread.sleep(200);
    }
    return false;
  }

  /** The number of open Channel Access channels the console reports. */
  private static int channelCount() throws IOException, InterruptedException {
    Matcher matcher = Pattern.compile("<td id=\"channel-count\">([0-9,]+)</td>").matcher(console());
    assertTrue("No channel count on the console", matcher.find());
    return Integer.parseInt(matcher.group(1).replace(",", ""));
  }

  private static String console() throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder().uri(URI.create("http://localhost:8080/epics2web/console")).build();
    return HTTP.send(request, HttpResponse.BodyHandlers.ofString()).body();
  }
}
