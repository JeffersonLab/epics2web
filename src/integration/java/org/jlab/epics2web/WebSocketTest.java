package org.jlab.epics2web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.junit.*;
import org.junit.rules.Timeout;

/**
 * Tests the monitor WebSocket against the test IOC. HELLO changes every 0.2 seconds; channel1 stays
 * at 0.
 */
public class WebSocketTest {

  private static final URI MONITOR_URI = URI.create("ws://localhost:8080/epics2web/monitor");
  private static final long WAIT_SECONDS = 5;

  private final List<Client> clients = new ArrayList<>();

  @Rule public Timeout globalTimeout = Timeout.seconds(30);

  @After
  public void tearDown() {
    for (Client client : clients) {
      client.socket.abort();
    }
  }

  @Test
  public void simpleTest() throws Exception {
    Client client = connect();
    client.send("{\"type\": \"monitor\",\"pvs\": [\"channel1\"]}");

    JsonObject info = client.next(isMessage("info", "channel1"));
    assertTrue("channel1 not connected", info.getBoolean("connected"));
    assertEquals("DBR_DOUBLE", info.getString("datatype"));
    assertEquals(1, info.getInt("count"));

    assertEquals(0.0, valueOf(client.next(isMessage("update", "channel1"))), 0.1);
  }

  @Test
  public void otherClientKeepsUpdatesAfterClear() throws Exception {
    assertOtherClientKeepsUpdates(
        leaving -> leaving.send("{\"type\": \"clear\",\"pvs\": [\"HELLO\"]}"));
  }

  @Test
  public void otherClientKeepsUpdatesAfterClose() throws Exception {
    assertOtherClientKeepsUpdates(leaving -> leaving.socket.sendClose(1000, "Done").join());
  }

  @Test
  public void otherClientKeepsUpdatesAfterAbort() throws Exception {
    assertOtherClientKeepsUpdates(leaving -> leaving.socket.abort());
  }

  /** Clearing a PV stops its updates to that client. */
  @Test
  public void clearStopsUpdates() throws Exception {
    Client client = connect();
    client.send("{\"type\": \"monitor\",\"pvs\": [\"HELLO\"]}");
    client.next(isMessage("update", "HELLO"));

    client.send("{\"type\": \"clear\",\"pvs\": [\"HELLO\"]}");

    // Let updates already on their way arrive, then expect no more.
    Thread.sleep(1000);
    client.messages.clear();
    assertNull(
        "Update received after clear",
        client.messages.poll(1, TimeUnit.SECONDS)); // HELLO would send about 5 in this time
  }

  /** Two clients monitor HELLO; after one leaves, the other must still get changing values. */
  private void assertOtherClientKeepsUpdates(Consumer<Client> leave) throws Exception {
    Client staying = connect();
    Client leaving = connect();

    for (Client client : List.of(staying, leaving)) {
      client.send("{\"type\": \"monitor\",\"pvs\": [\"HELLO\"]}");
      assertTrue(
          "HELLO not connected", client.next(isMessage("info", "HELLO")).getBoolean("connected"));
      client.next(isMessage("update", "HELLO"));
    }

    leave.accept(leaving);

    // Updates queued before the other client left don't count.
    Thread.sleep(500);
    staying.messages.clear();

    // Wait for a change, so this isn't only a value sent before the other client left.
    double first = valueOf(staying.next(isMessage("update", "HELLO")));
    while (valueOf(staying.next(isMessage("update", "HELLO"))) == first) {}
  }

  private Client connect() {
    Client client = new Client();
    client.socket =
        HttpClient.newHttpClient().newWebSocketBuilder().buildAsync(MONITOR_URI, client).join();
    clients.add(client);
    return client;
  }

  private static double valueOf(JsonObject update) {
    return update.getJsonNumber("value").doubleValue();
  }

  private static Predicate<JsonObject> isMessage(String type, String pv) {
    return msg -> type.equals(msg.getString("type", null)) && pv.equals(msg.getString("pv", null));
  }

  /** Collects the messages the server sends, so tests can wait for the ones they expect. */
  private static class Client implements WebSocket.Listener {
    private final BlockingQueue<JsonObject> messages = new LinkedBlockingQueue<>();
    private final StringBuilder partial = new StringBuilder();
    private WebSocket socket;

    void send(String text) {
      socket.sendText(text, true).join();
    }

    /** Waits for the next message that matches, skipping others; fails if none arrives. */
    JsonObject next(Predicate<JsonObject> matcher) throws InterruptedException {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
      while (true) {
        JsonObject msg = messages.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
        if (msg == null) {
          fail("No matching message within " + WAIT_SECONDS + " seconds");
        }
        if (matcher.test(msg)) {
          return msg;
        }
      }
    }

    @Override
    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
      partial.append(data);
      if (last) {
        try (JsonReader reader = Json.createReader(new StringReader(partial.toString()))) {
          messages.add(reader.readObject());
        }
        partial.setLength(0);
      }
      return WebSocket.Listener.super.onText(ws, data, last);
    }
  }
}
