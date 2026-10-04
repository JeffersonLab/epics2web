package org.jlab.epics2web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

/**
 * Tests /healthcheck (#28). Needs build.yaml's 2 second HEALTHCHECK_GRACE_SECONDS. The disconnect
 * test stops and starts the softioc container, so it needs the docker command.
 */
public class HealthcheckTest {

  private static final HttpClient HTTP = HttpClient.newHttpClient();

  @Rule public Timeout globalTimeout = Timeout.seconds(90);

  /** A PV that never connects may just not exist, so it's listed but doesn't fail strict mode. */
  @Test
  public void neverConnectedPvIsListedWithoutFailingStrictMode() throws Exception {
    String pv = "epics2web:test:healthcheck:" + UUID.randomUUID();
    WebSocket socket = monitor(pv);
    try {
      JsonObject entry = waitForEntry(pv, true);
      assertEquals("CONNECTING", entry.getString("state"));
      assertEquals(200, get("healthcheck").statusCode());
      assertEquals(200, get("healthcheck?strict=true").statusCode());
    } finally {
      socket.abort();
    }
    waitForEntry(pv, false);
  }

  /**
   * A PV that disconnects is reported once the grace period after the disconnect passes. HELLO
   * changes every 0.2 s; channel1 hasn't changed since the IOC started, which the old check mistook
   * for time disconnected.
   */
  @Test
  public void disconnectedPvFailsStrictModeOnly() throws Exception {
    assumeTrue("docker command not available", docker("ps") == 0);

    WebSocket socket = monitor("channel1");
    try {
      // Longer than the grace period, so a check of time since the last value would fail at once
      Thread.sleep(3_000);
      assertEquals(0, docker("stop", "-t", "1", "softioc"));
      long stopped = System.nanoTime();

      // Disconnected by now, but still within the grace period after the disconnect
      Thread.sleep(1_000);
      assertNull(entry(get("healthcheck"), "channel1"));

      JsonObject entry = waitForEntry("channel1", true);
      long reportedAfter = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - stopped);
      assertEquals("DISCONNECTED", entry.getString("state"));
      // Measured from the disconnect, seconds ago, not from channel1's last value change
      double minutes = Double.parseDouble(entry.getString("disconnected_minutes"));
      assertTrue("Disconnected for " + minutes + " minutes", minutes < 0.5);
      assertTrue("Reported after only " + reportedAfter + " ms", reportedAfter >= 2_000);
      assertEquals(200, get("healthcheck").statusCode());
      assertEquals(503, get("healthcheck?strict=true").statusCode());
    } finally {
      docker("start", "softioc");
      waitForEntry("channel1", false); // Reconnected
      socket.abort();
    }
    assertEquals(200, get("healthcheck?strict=true").statusCode());
  }

  private static WebSocket monitor(String pv) {
    WebSocket socket =
        HTTP.newWebSocketBuilder()
            .buildAsync(
                URI.create("ws://localhost:8080/epics2web/monitor"), new WebSocket.Listener() {})
            .join();
    socket.sendText("{\"type\": \"monitor\",\"pvs\": [\"" + pv + "\"]}", true).join();
    return socket;
  }

  /** Waits for the healthcheck to list, or stop listing, a PV; returns its entry if listed. */
  private static JsonObject waitForEntry(String pv, boolean listed) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (System.nanoTime() < deadline) {
      JsonObject entry = entry(get("healthcheck"), pv);
      if ((entry != null) == listed) {
        return entry;
      }
      Thread.sleep(200);
    }
    fail(listed ? "Healthcheck never listed " + pv : "Healthcheck still lists " + pv);
    return null;
  }

  private static JsonObject entry(HttpResponse<String> response, String pv) {
    try (JsonReader reader = Json.createReader(new StringReader(response.body()))) {
      JsonArray entries = reader.readArray();
      for (int i = 0; i < entries.size(); i++) {
        if (pv.equals(entries.getJsonObject(i).getString("name"))) {
          return entries.getJsonObject(i);
        }
      }
      return null;
    }
  }

  private static HttpResponse<String> get(String path) throws Exception {
    return HTTP.send(
        HttpRequest.newBuilder().uri(URI.create("http://localhost:8080/epics2web/" + path)).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static int docker(String... args) {
    String[] command = new String[args.length + 1];
    command[0] = "docker";
    System.arraycopy(args, 0, command, 1, args.length);
    try {
      return new ProcessBuilder(command).redirectErrorStream(true).start().waitFor();
    } catch (Exception e) {
      return -1;
    }
  }
}
