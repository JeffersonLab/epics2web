package org.jlab.epics2web;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.*;
import org.junit.rules.Timeout;

public class WebSocketTest {

  private CountDownLatch latch;
  private WebSocket socket;

  @Rule public Timeout globalTimeout = Timeout.seconds(10);

  @Before
  public void setUp() {
    latch = new CountDownLatch(1);

    socket =
        HttpClient.newHttpClient()
            .newWebSocketBuilder()
            .buildAsync(
                URI.create("ws://localhost:8080/epics2web/monitor"), new WebSocketClient(latch))
            .join();
  }

  @After
  public void tearDown() {
    socket.sendClose(1000, "Done");
  }

  @Test
  public void simpleTest() throws Exception {
    socket.sendText("{\"type\": \"monitor\",\"pvs\": [\"channel1\"]}", true);
    latch.await(5, TimeUnit.SECONDS);
  }

  private static class WebSocketClient implements WebSocket.Listener {
    private final CountDownLatch latch;

    public WebSocketClient(CountDownLatch latch) {
      this.latch = latch;
    }

    @Override
    public void onOpen(WebSocket ws) {
      System.out.println("onOpen: ");
      WebSocket.Listener.super.onOpen(ws);
    }

    @Override
    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
      System.out.println("onText: " + data);
      latch.countDown();
      return WebSocket.Listener.super.onText(ws, data, last);
    }

    @Override
    public void onError(WebSocket ws, Throwable error) {
      System.out.println("onError: " + ws.toString());
      WebSocket.Listener.super.onError(ws, error);
    }
  }
}
