package org.jlab.epics2web.websocket;

import static org.jlab.epics2web.epics.TestDbrs.doubleDbr;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import gov.aps.jca.dbr.DBRType;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import java.io.StringReader;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jlab.epics2web.epics.ChannelManager;
import org.jlab.epics2web.epics.PvListener;
import org.jlab.epics2web.websocket.WebSocketSessionManager.MessageType;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

/**
 * Tests WebSocketSessionManager with a fake session and a channel manager that records
 * subscriptions, so no IOC or server is needed.
 */
public class WebSocketSessionManagerTest {

  private final RecordingChannelManager channelManager = new RecordingChannelManager();
  private final WebSocketSessionManager manager =
      new WebSocketSessionManager(channelManager, Duration.ofSeconds(60));

  /** A send that blocks on a full queue fails the test instead of hanging it. */
  @Rule public Timeout timeout = Timeout.seconds(5);

  @Test
  public void sendPong() throws Exception {
    FakeSession client = connect("1");

    manager.sendPong(client.session);

    assertEquals("{\"type\":\"pong\"}", client.writeQueue.poll());
  }

  @Test
  public void sendInfoForConnectedPv() {
    FakeSession client = connect("1");

    manager.sendInfo(client.session, "pv1", true, DBRType.DOUBLE, 1, null);

    assertEquals(
        "{\"type\":\"info\",\"pv\":\"pv1\",\"connected\":true,\"datatype\":\"DBR_DOUBLE\",\"count\":1}",
        client.writeQueue.poll());
  }

  @Test
  public void sendInfoIncludesEnumLabels() {
    FakeSession client = connect("1");

    manager.sendInfo(client.session, "pv1", true, DBRType.ENUM, 1, new String[] {"OFF", "ON"});

    assertEquals(
        "{\"type\":\"info\",\"pv\":\"pv1\",\"connected\":true,\"datatype\":\"DBR_ENUM\",\"count\":1,"
            + "\"enum-labels\":[\"OFF\",\"ON\"]}",
        client.writeQueue.poll());
  }

  @Test
  public void sendInfoForPvThatCouldNotConnect() {
    FakeSession client = connect("1");

    manager.sendInfo(client.session, "pv1", false, null, null, null);

    assertEquals(
        "{\"type\":\"info\",\"pv\":\"pv1\",\"connected\":false}", client.writeQueue.poll());
  }

  @Test
  public void sendUpdate() {
    FakeSession client = connect("1");

    manager.sendUpdate(client.session, "pv1", doubleDbr(1.5));

    assertEquals("{\"type\":\"update\",\"pv\":\"pv1\",\"value\":1.5}", client.writeQueue.poll());
  }

  @Test
  public void sendToClosedSessionIsIgnored() {
    FakeSession client = connect("1");
    client.open = false;

    manager.send(client.session, MessageType.UPDATE, "pv1", "message");

    assertNull(client.writeQueue.poll());
    assertEquals(0, client.droppedMessageCount());
  }

  /** A client that isn't reading must not block the sender; messages that don't fit count. */
  @Test
  public void fullQueueCountsDroppedMessages() {
    FakeSession client = connect("1", 2);

    manager.send(client.session, MessageType.UPDATE, "pv1", "message1");
    manager.send(client.session, MessageType.UPDATE, "pv2", "message2");
    manager.send(client.session, MessageType.UPDATE, "pv3", "message3");
    manager.send(client.session, MessageType.UPDATE, "pv4", "message4");

    assertEquals(2, client.writeQueue.size());
    assertEquals(2, client.droppedMessageCount());
  }

  /** A client that falls behind gets each PV's latest value and every info message (#26). */
  @Test
  public void fullQueueKeepsLatestUpdatesAndInfo() throws Exception {
    FakeSession client = connect("1", 2);

    manager.sendUpdate(client.session, "pv1", doubleDbr(1.0));
    manager.sendUpdate(client.session, "pv2", doubleDbr(1.0));
    manager.sendUpdate(client.session, "pv1", doubleDbr(2.0));
    manager.sendInfo(client.session, "pv3", false, null, null, null);
    manager.sendPong(client.session);

    assertEquals("{\"type\":\"update\",\"pv\":\"pv1\",\"value\":2.0}", client.writeQueue.poll());
    assertEquals("{\"type\":\"update\",\"pv\":\"pv2\",\"value\":1.0}", client.writeQueue.poll());
    assertEquals(
        "{\"type\":\"info\",\"pv\":\"pv3\",\"connected\":false}", client.writeQueue.poll());
    assertNull(client.writeQueue.poll());
    assertEquals(1, client.droppedMessageCount()); // The pong
  }

  @Test
  public void getPvSetFromJsonKeepsOnlyStrings() {
    JsonArray pvs =
        Json.createReader(new StringReader("[\"pv1\", 7, \"pv2\", null, \"pv1\"]")).readArray();

    assertEquals(Set.of("pv1", "pv2"), manager.getPvSetFromJson(pvs));
  }

  @Test
  public void addPvsSubscribesEachPvExceptEmpty() {
    FakeSession client = connect("1");

    manager.addPvs(client.session, new HashSet<>(Set.of("pv1", "pv2", "")));

    assertEquals(Set.of("pv1", "pv2"), channelManager.pvsOf(listenerOf(client)));
  }

  @Test
  public void sessionKeepsOneListenerAcrossRequests() {
    FakeSession client = connect("1");

    manager.addPvs(client.session, new HashSet<>(Set.of("pv1")));
    PvListener first = listenerOf(client);
    manager.addPvs(client.session, new HashSet<>(Set.of("pv2")));

    assertSame(first, listenerOf(client));
    assertEquals(1, channelManager.getListenerMap().size());
    assertEquals(Set.of("pv1", "pv2"), channelManager.pvsOf(first));
  }

  @Test
  public void sessionsHaveSeparateListeners() {
    FakeSession client1 = connect("1");
    FakeSession client2 = connect("2");

    manager.addPvs(client1.session, new HashSet<>(Set.of("pv1")));
    manager.addPvs(client2.session, new HashSet<>(Set.of("pv1")));

    assertNotSame(listenerOf(client1), listenerOf(client2));
  }

  /** Updates and info for a PV go to the session whose listener the channel manager notifies. */
  @Test
  public void listenerNotificationsReachItsSession() {
    FakeSession client1 = connect("1");
    FakeSession client2 = connect("2");
    manager.addPvs(client1.session, new HashSet<>(Set.of("pv1")));
    manager.addPvs(client2.session, new HashSet<>(Set.of("pv1")));

    listenerOf(client1).notifyPvInfo("pv1", true, DBRType.DOUBLE, 1, null);
    listenerOf(client1).notifyPvUpdate("pv1", doubleDbr(2.0));

    assertEquals(
        "{\"type\":\"info\",\"pv\":\"pv1\",\"connected\":true,\"datatype\":\"DBR_DOUBLE\",\"count\":1}",
        client1.writeQueue.poll());
    assertEquals("{\"type\":\"update\",\"pv\":\"pv1\",\"value\":2.0}", client1.writeQueue.poll());
    assertNull(client2.writeQueue.poll());
  }

  @Test
  public void removePvsUnsubscribesEachPvExceptEmpty() {
    FakeSession client = connect("1");
    manager.addPvs(client.session, new HashSet<>(Set.of("pv1", "pv2", "pv3")));

    manager.removePvs(client.session, new HashSet<>(Set.of("pv1", "pv2", "")));

    assertEquals(Set.of("pv3"), channelManager.pvsOf(listenerOf(client)));
  }

  @Test
  public void removeClientUnsubscribesAll() {
    FakeSession client = connect("1");
    manager.addPvs(client.session, new HashSet<>(Set.of("pv1", "pv2")));
    PvListener listener = listenerOf(client);

    manager.removeClient(client.session);

    assertEquals(List.of(listener), channelManager.removedAll);
    assertNull(channelManager.pvsOf(listener));
  }

  @Test
  public void recordInteractionDateOnlyForOpenSessions() {
    FakeSession open = connect("1");
    FakeSession closed = connect("2");
    closed.open = false;

    manager.recordInteractionDate(open.session);
    manager.recordInteractionDate(closed.session);
    manager.recordInteractionDate(null);

    assertTrue(open.userProperties.get("lastUpdated") instanceof Date);
    assertNull(closed.userProperties.get("lastUpdated"));
  }

  @Test
  public void clientMapListsOpenSessionsWithTheirPvs() {
    FakeSession open = connect("1", 1);
    open.userProperties.put("ip", "10.0.0.1");
    open.userProperties.put("name", "my-screen");
    open.userProperties.put("agent", "test-agent");
    FakeSession closed = connect("2");
    manager.addPvs(open.session, new HashSet<>(Set.of("pv1", "pv2")));
    manager.addPvs(closed.session, new HashSet<>(Set.of("pv3")));
    closed.open = false;
    manager.send(open.session, MessageType.UPDATE, "pv1", "queued");
    manager.send(open.session, MessageType.UPDATE, "pv2", "dropped");

    Map<SessionInfo, Set<String>> clientMap = manager.getClientMap();

    assertEquals(1, clientMap.size());
    SessionInfo info = clientMap.keySet().iterator().next();
    assertEquals("1", info.getId());
    assertEquals("10.0.0.1", info.getIp());
    assertEquals("my-screen", info.getName());
    assertEquals("test-agent", info.getAgent());
    assertEquals(1, info.getDroppedMessageCount());
    assertEquals(Set.of("pv1", "pv2"), clientMap.get(info));
  }

  /** Closed sessions must not stay in the manager (they held their write queues forever). */
  @Test
  public void removeClientForgetsSession() {
    FakeSession monitoring = connect("1");
    FakeSession idle = connect("2");
    manager.addPvs(monitoring.session, new HashSet<>(Set.of("pv1")));

    manager.removeClient(monitoring.session);
    manager.removeClient(idle.session);

    assertTrue(manager.toSet().isEmpty());
  }

  @Test
  public void removeClientOfUnknownSessionDoesNotAddIt() {
    FakeSession client = new FakeSession("1");

    manager.removeClient(client.session);

    assertTrue(manager.toSet().isEmpty());
    assertTrue(channelManager.removedAll.isEmpty());
  }

  @Test
  public void requestsFromRemovedSessionAreIgnored() {
    FakeSession client = connect("1");
    manager.removeClient(client.session);

    manager.addPvs(client.session, new HashSet<>(Set.of("pv1")));
    manager.removePvs(client.session, new HashSet<>(Set.of("pv1")));

    assertTrue(manager.toSet().isEmpty());
    assertTrue(channelManager.getListenerMap().isEmpty());
  }

  /** A session that closes while its monitor request is being handled must not keep monitors. */
  @Test
  public void sessionRemovedDuringAddPvsKeepsNoPvs() {
    FakeSession client = connect("1");
    PvListener listener = manager.listenerMap.get(client.session);
    channelManager.onAddPv = () -> manager.removeClient(client.session);

    manager.addPvs(client.session, new HashSet<>(Set.of("pv1", "pv2")));

    assertNull(channelManager.pvsOf(listener));
  }

  @Test
  public void clientMapIncludesSessionsWithoutPvs() {
    connect("1");

    Map<SessionInfo, Set<String>> clientMap = manager.getClientMap();

    assertEquals(1, clientMap.size());
    assertEquals(Set.of(), clientMap.values().iterator().next());
  }

  @Test
  public void purgeClosesAndRemovesStaleSessions() {
    FakeSession fresh = connect("fresh");
    manager.recordInteractionDate(fresh.session);
    FakeSession stale = connect("stale");
    stale.userProperties.put("lastUpdated", secondsAgo(61));
    FakeSession neverInteracted = connect("never");
    manager.addPvs(stale.session, new HashSet<>(Set.of("pv1")));
    PvListener staleListener = listenerOf(stale);

    manager.purgeStaleSessions(Runnable::run);

    assertEquals(Set.of(fresh.session), manager.toSet());
    assertFalse(fresh.closed);
    assertTrue(stale.closed);
    assertTrue(neverInteracted.closed);
    assertNull(channelManager.pvsOf(staleListener));
  }

  @Test
  public void purgeRemovesClosedSessions() {
    FakeSession client = connect("1");
    manager.recordInteractionDate(client.session);
    client.open = false;

    manager.purgeStaleSessions(Runnable::run);

    assertTrue(manager.toSet().isEmpty());
  }

  @Test
  public void purgeKeepsSessionThatInteractedRecently() {
    FakeSession client = connect("1");
    client.userProperties.put("lastUpdated", secondsAgo(50));

    manager.purgeStaleSessions(Runnable::run);

    assertEquals(Set.of(client.session), manager.toSet());
    assertFalse(client.closed);
  }

  @Test
  public void pingAllSessionsPingsEachOpenSession() {
    FakeSession client1 = connect("1");
    FakeSession client2 = connect("2");
    FakeSession closed = connect("3");
    closed.open = false;

    manager.pingAllSessions(Runnable::run);

    assertEquals(1, client1.pings.get());
    assertEquals(1, client2.pings.get());
    assertEquals(0, closed.pings.get());
  }

  @Test
  public void failedPingClosesAndRemovesSession() {
    FakeSession healthy = connect("1");
    FakeSession broken = connect("2");
    broken.failPings = true;

    manager.pingAllSessions(Runnable::run);

    assertEquals(Set.of(healthy.session), manager.toSet());
    assertTrue(broken.closed);
    assertFalse(healthy.closed);
  }

  /** Registers the session, as MonitorEndpoint.onOpen does. */
  private FakeSession connect(String id, int queueSize) {
    FakeSession client = new FakeSession(id, queueSize);
    manager.addClient(client.session);
    return client;
  }

  private FakeSession connect(String id) {
    return connect(id, 100);
  }

  private static Date secondsAgo(long seconds) {
    return Date.from(Instant.now().minusSeconds(seconds));
  }

  private PvListener listenerOf(FakeSession client) {
    PvListener listener = manager.listenerMap.get(client.session);
    assertNotNull("No listener for " + client.session, listener);
    return listener;
  }

  /** Records subscriptions instead of creating channels. */
  private static class RecordingChannelManager extends ChannelManager {
    private final Map<PvListener, Set<String>> pvsByListener = new ConcurrentHashMap<>();
    private final List<PvListener> removedAll = new ArrayList<>();
    private Runnable onAddPv = () -> {};

    RecordingChannelManager() {
      super(null, null, null);
    }

    @Override
    public void addPv(PvListener listener, String pv) {
      onAddPv.run();
      pvsByListener.computeIfAbsent(listener, k -> ConcurrentHashMap.newKeySet()).add(pv);
    }

    @Override
    public void removePv(PvListener listener, String pv) {
      Set<String> pvs = pvsByListener.get(listener);
      if (pvs != null) {
        pvs.remove(pv);
      }
    }

    @Override
    public void removeAll(PvListener listener) {
      pvsByListener.remove(listener);
      removedAll.add(listener);
    }

    @Override
    public Map<PvListener, Set<String>> getListenerMap() {
      return pvsByListener;
    }

    Set<String> pvsOf(PvListener listener) {
      return pvsByListener.get(listener);
    }
  }
}
