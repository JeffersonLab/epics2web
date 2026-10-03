package org.jlab.epics2web.websocket;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

public class WriteQueueTest {

  @Rule public Timeout timeout = Timeout.seconds(5);

  @Test
  public void keepsOrder() {
    WriteQueue queue = new WriteQueue(10);

    queue.offerInfo("pv1", "info1");
    queue.offerUpdate("pv1", "update1");
    queue.offer("pong");
    queue.offerUpdate("pv2", "update2");

    assertEquals(List.of("info1", "update1", "pong", "update2"), drain(queue));
  }

  /** A newer update replaces the waiting one in its place, so the latest value isn't delayed. */
  @Test
  public void newerUpdateReplacesWaitingUpdateForSamePv() {
    WriteQueue queue = new WriteQueue(10);

    queue.offerUpdate("pv1", "pv1 a");
    queue.offerUpdate("pv2", "pv2 a");
    queue.offerUpdate("pv1", "pv1 b");
    queue.offerUpdate("pv1", "pv1 c");

    assertEquals(List.of("pv1 c", "pv2 a"), drain(queue));
  }

  @Test
  public void updateAfterSentUpdateIsQueued() {
    WriteQueue queue = new WriteQueue(10);
    queue.offerUpdate("pv1", "pv1 a");
    assertEquals("pv1 a", queue.poll());

    queue.offerUpdate("pv1", "pv1 b");

    assertEquals(List.of("pv1 b"), drain(queue));
  }

  /** An update must not move ahead of an info message for its PV, such as a disconnect. */
  @Test
  public void updateIsNotMergedAcrossInfoForSamePv() {
    WriteQueue queue = new WriteQueue(10);

    queue.offerUpdate("pv1", "pv1 a");
    queue.offerInfo("pv1", "pv1 disconnected");
    queue.offerInfo("pv1", "pv1 connected");
    queue.offerUpdate("pv1", "pv1 b");
    queue.offerUpdate("pv1", "pv1 c");

    assertEquals(List.of("pv1 a", "pv1 disconnected", "pv1 connected", "pv1 c"), drain(queue));
  }

  @Test
  public void infoForOtherPvDoesNotStopMerging() {
    WriteQueue queue = new WriteQueue(10);

    queue.offerUpdate("pv1", "pv1 a");
    queue.offerInfo("pv2", "pv2 connected");
    queue.offerUpdate("pv1", "pv1 b");

    assertEquals(List.of("pv1 b", "pv2 connected"), drain(queue));
  }

  @Test
  public void fullQueueStillMergesUpdates() {
    WriteQueue queue = new WriteQueue(2);
    queue.offerUpdate("pv1", "pv1 a");
    queue.offerUpdate("pv2", "pv2 a");

    assertTrue(queue.offerUpdate("pv1", "pv1 b"));

    assertEquals(List.of("pv1 b", "pv2 a"), drain(queue));
  }

  @Test
  public void fullQueueDropsUpdateForPvWithNothingWaiting() {
    WriteQueue queue = new WriteQueue(2);
    queue.offerUpdate("pv1", "pv1 a");
    queue.offerUpdate("pv2", "pv2 a");

    assertFalse(queue.offerUpdate("pv3", "pv3 a"));
    assertFalse(queue.offer("pong"));

    assertEquals(List.of("pv1 a", "pv2 a"), drain(queue));
  }

  @Test
  public void fullQueueKeepsInfo() {
    WriteQueue queue = new WriteQueue(2);
    queue.offerUpdate("pv1", "pv1 a");
    queue.offerUpdate("pv2", "pv2 a");

    queue.offerInfo("pv3", "pv3 connected");

    assertEquals(List.of("pv1 a", "pv2 a", "pv3 connected"), drain(queue));
  }

  @Test
  public void takeWaitsForMessage() throws Exception {
    WriteQueue queue = new WriteQueue(10);
    CompletableFuture<String> taken =
        CompletableFuture.supplyAsync(
            () -> {
              try {
                return queue.take();
              } catch (InterruptedException e) {
                throw new RuntimeException(e);
              }
            });

    Thread.sleep(100);
    assertFalse(taken.isDone());
    queue.offerUpdate("pv1", "pv1 a");

    assertEquals("pv1 a", taken.get(1, TimeUnit.SECONDS));
  }

  @Test(expected = InterruptedException.class)
  public void takeCanBeInterrupted() throws Exception {
    WriteQueue queue = new WriteQueue(10);
    Thread.currentThread().interrupt();

    queue.take();
  }

  private static List<String> drain(WriteQueue queue) {
    List<String> messages = new ArrayList<>();
    for (String msg = queue.poll(); msg != null; msg = queue.poll()) {
      messages.add(msg);
    }
    assertNull(queue.poll());
    assertEquals(0, queue.size());
    return messages;
  }
}
