package org.jlab.epics2web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

/** Tests /caget alongside other /caget requests and monitors of the same PVs (#29). */
public class CaGetConcurrencyTest {

  private static final HttpClient HTTP = HttpClient.newHttpClient();

  @Rule public Timeout globalTimeout = Timeout.seconds(90);

  /** A PV that never connects must not hold up requests for other PVs. */
  @Test
  public void missingPvDoesNotDelayOtherRequests() throws Exception {
    AtomicBoolean stop = new AtomicBoolean();
    Thread missing =
        new Thread(
            () -> {
              while (!stop.get()) {
                caget("pv=epics2web:test:missing");
              }
            });
    missing.start();

    try {
      Thread.sleep(500); // Let a request for the missing PV start waiting
      for (int i = 0; i < 20; i++) {
        long start = System.nanoTime();
        String body = caget("pv=channel1");
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertTrue("Request failed: " + body, body.contains("\"data\""));
        assertTrue("Request took " + millis + " ms", millis < 1_000);
      }
    } finally {
      stop.set(true);
      missing.join();
    }
  }

  /**
   * Requests and monitors of the same PV create and destroy channels that CAJ shares by name; they
   * must not hand each other a closed channel.
   */
  @Test
  public void cagetAndMonitorOfSamePvDoNotInterfere() throws Exception {
    AtomicBoolean stop = new AtomicBoolean();
    LongAdder successes = new LongAdder();
    Map<String, Integer> failures = new ConcurrentHashMap<>();
    List<Thread> threads = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      Thread thread =
          new Thread(
              () -> {
                while (!stop.get()) {
                  String body = caget("pv=channel2");
                  if (body.contains("\"data\"")) {
                    successes.increment();
                  } else {
                    failures.merge(body, 1, Integer::sum);
                  }
                }
              });
      thread.start();
      threads.add(thread);
    }

    BlockingQueue<String> messages = new LinkedBlockingQueue<>();
    WebSocket socket =
        HTTP.newWebSocketBuilder()
            .buildAsync(
                URI.create("ws://localhost:8080/epics2web/monitor"),
                new WebSocket.Listener() {
                  private final StringBuilder partial = new StringBuilder();

                  @Override
                  public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                    partial.append(data);
                    if (last) {
                      messages.add(partial.toString());
                      partial.setLength(0);
                    }
                    return WebSocket.Listener.super.onText(ws, data, last);
                  }
                })
            .join();

    AtomicInteger monitorsWithoutConnectedInfo = new AtomicInteger();
    try {
      for (int i = 0; i < 100; i++) {
        socket.sendText("{\"type\": \"monitor\",\"pvs\": [\"channel2\"]}", true).join();
        if (!waitForConnectedInfo(messages)) {
          monitorsWithoutConnectedInfo.incrementAndGet();
        }
        socket.sendText("{\"type\": \"clear\",\"pvs\": [\"channel2\"]}", true).join();
        messages.clear();
      }
    } finally {
      stop.set(true);
      for (Thread thread : threads) {
        thread.join();
      }
      socket.abort();
    }

    assertEquals("Monitors that never reported connected", 0, monitorsWithoutConnectedInfo.get());
    assertEquals("Failed requests (of " + successes.sum() + " that succeeded)", Map.of(), failures);
    assertTrue(successes.sum() > 0);
  }

  private static boolean waitForConnectedInfo(BlockingQueue<String> messages)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (true) {
      String msg = messages.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
      if (msg == null) {
        return false;
      }
      if (msg.contains("\"type\":\"info\"") && msg.contains("\"pv\":\"channel2\"")) {
        return msg.contains("\"connected\":true");
      }
    }
  }

  private static String caget(String query) {
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:8080/epics2web/caget?" + query))
            .build();
    try {
      return HTTP.send(request, HttpResponse.BodyHandlers.ofString()).body();
    } catch (Exception e) {
      return e.toString();
    }
  }
}
