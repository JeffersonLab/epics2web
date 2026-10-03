package org.jlab.epics2web.websocket;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import gov.aps.jca.dbr.DBRType;
import gov.aps.jca.dbr.DBR_Double;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jlab.epics2web.epics.ChannelManager;
import org.jlab.epics2web.epics.PvListener;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

/**
 * Tests WebSocketSessionManager with a fake session and a channel manager that records
 * subscriptions, so no IOC or server is needed. Stale session purging and server pings (#27), and
 * which message a full queue drops (#26), are left to the fixes for those issues.
 */
public class WebSocketSessionManagerTest {

  private final RecordingChannelManager channelManager = new RecordingChannelManager();
  private final WebSocketSessionManager manager = new WebSocketSessionManager(channelManager);

  /** A send that blocks on a full queue fails the test instead of hanging it. */
  @Rule public Timeout timeout = Timeout.seconds(5);

  @Test
  public void sendPong() throws Exception {
    FakeSession client = new FakeSession("1");

    manager.sendPong(client.session);

    assertEquals("{\"type\":\"pong\"}", client.writeQueue.poll());
  }

  @Test
  public void sendInfoForConnectedPv() {
    FakeSession client = new FakeSession("1");

    manager.sendInfo(client.session, "pv1", true, DBRType.DOUBLE, 1, null);

    assertEquals(
        "{\"type\":\"info\",\"pv\":\"pv1\",\"connected\":true,\"datatype\":\"DBR_DOUBLE\",\"count\":1}",
        client.writeQueue.poll());
  }

  @Test
  public void sendInfoIncludesEnumLabels() {
    FakeSession client = new FakeSession("1");

    manager.sendInfo(client.session, "pv1", true, DBRType.ENUM, 1, new String[] {"OFF", "ON"});

    assertEquals(
        "{\"type\":\"info\",\"pv\":\"pv1\",\"connected\":true,\"datatype\":\"DBR_ENUM\",\"count\":1,"
            + "\"enum-labels\":[\"OFF\",\"ON\"]}",
        client.writeQueue.poll());
  }

  @Test
  public void sendInfoForPvThatCouldNotConnect() {
    FakeSession client = new FakeSession("1");

    manager.sendInfo(client.session, "pv1", false, null, null, null);

    assertEquals(
        "{\"type\":\"info\",\"pv\":\"pv1\",\"connected\":false}", client.writeQueue.poll());
  }

  @Test
  public void sendUpdate() {
    FakeSession client = new FakeSession("1");

    manager.sendUpdate(client.session, "pv1", new DBR_Double(new double[] {1.5}));

    assertEquals("{\"type\":\"update\",\"pv\":\"pv1\",\"value\":1.5}", client.writeQueue.poll());
  }

  @Test
  public void sendToClosedSessionIsIgnored() {
    FakeSession client = new FakeSession("1");
    client.open = false;

    manager.send(client.session, "pv1", "message");

    assertTrue(client.writeQueue.isEmpty());
    assertEquals(0, client.droppedMessageCount());
  }

  /** A client that isn't reading must not block the sender; messages that don't fit count. */
  @Test
  public void fullQueueCountsDroppedMessages() {
    FakeSession client = new FakeSession("1", 2);

    manager.send(client.session, "pv1", "message1");
    manager.send(client.session, "pv2", "message2");
    manager.send(client.session, "pv3", "message3");
    manager.send(client.session, "pv4", "message4");

    assertEquals(2, client.writeQueue.size());
    assertEquals(2, client.droppedMessageCount());
  }

  @Test
  public void getPvSetFromJsonKeepsOnlyStrings() {
    JsonArray pvs =
        Json.createReader(new StringReader("[\"pv1\", 7, \"pv2\", null, \"pv1\"]")).readArray();

    assertEquals(Set.of("pv1", "pv2"), manager.getPvSetFromJson(pvs));
  }

  @Test
  public void addPvsSubscribesEachPvExceptEmpty() {
    FakeSession client = new FakeSession("1");

    manager.addPvs(client.session, new HashSet<>(Set.of("pv1", "pv2", "")));

    assertEquals(Set.of("pv1", "pv2"), channelManager.pvsOf(listenerOf(client)));
  }

  @Test
  public void sessionKeepsOneListenerAcrossRequests() {
    FakeSession client = new FakeSession("1");

    manager.addPvs(client.session, new HashSet<>(Set.of("pv1")));
    PvListener first = listenerOf(client);
    manager.addPvs(client.session, new HashSet<>(Set.of("pv2")));

    assertSame(first, listenerOf(client));
    assertEquals(1, channelManager.getListenerMap().size());
    assertEquals(Set.of("pv1", "pv2"), channelManager.pvsOf(first));
  }

  @Test
  public void sessionsHaveSeparateListeners() {
    FakeSession client1 = new FakeSession("1");
    FakeSession client2 = new FakeSession("2");

    manager.addPvs(client1.session, new HashSet<>(Set.of("pv1")));
    manager.addPvs(client2.session, new HashSet<>(Set.of("pv1")));

    assertNotSame(listenerOf(client1), listenerOf(client2));
  }

  /** Updates and info for a PV go to the session whose listener the channel manager notifies. */
  @Test
  public void listenerNotificationsReachItsSession() {
    FakeSession client1 = new FakeSession("1");
    FakeSession client2 = new FakeSession("2");
    manager.addPvs(client1.session, new HashSet<>(Set.of("pv1")));
    manager.addPvs(client2.session, new HashSet<>(Set.of("pv1")));

    listenerOf(client1).notifyPvInfo("pv1", true, DBRType.DOUBLE, 1, null);
    listenerOf(client1).notifyPvUpdate("pv1", new DBR_Double(new double[] {2.0}));

    assertEquals(
        "{\"type\":\"info\",\"pv\":\"pv1\",\"connected\":true,\"datatype\":\"DBR_DOUBLE\",\"count\":1}",
        client1.writeQueue.poll());
    assertEquals("{\"type\":\"update\",\"pv\":\"pv1\",\"value\":2.0}", client1.writeQueue.poll());
    assertTrue(client2.writeQueue.isEmpty());
  }

  @Test
  public void removePvsUnsubscribesEachPvExceptEmpty() {
    FakeSession client = new FakeSession("1");
    manager.addPvs(client.session, new HashSet<>(Set.of("pv1", "pv2", "pv3")));

    manager.removePvs(client.session, new HashSet<>(Set.of("pv1", "pv2", "")));

    assertEquals(Set.of("pv3"), channelManager.pvsOf(listenerOf(client)));
  }

  @Test
  public void removeClientUnsubscribesAll() {
    FakeSession client = new FakeSession("1");
    manager.addPvs(client.session, new HashSet<>(Set.of("pv1", "pv2")));
    PvListener listener = listenerOf(client);

    manager.removeClient(client.session);

    assertEquals(List.of(listener), channelManager.removedAll);
    assertNull(channelManager.pvsOf(listener));
  }

  @Test
  public void recordInteractionDateOnlyForOpenSessions() {
    FakeSession open = new FakeSession("1");
    FakeSession closed = new FakeSession("2");
    closed.open = false;

    manager.recordInteractionDate(open.session);
    manager.recordInteractionDate(closed.session);
    manager.recordInteractionDate(null);

    assertTrue(open.userProperties.get("lastUpdated") instanceof Date);
    assertNull(closed.userProperties.get("lastUpdated"));
  }

  @Test
  public void clientMapListsOpenSessionsWithTheirPvs() {
    FakeSession open = new FakeSession("1", 1);
    open.userProperties.put("ip", "10.0.0.1");
    open.userProperties.put("name", "my-screen");
    open.userProperties.put("agent", "test-agent");
    FakeSession closed = new FakeSession("2");
    manager.addPvs(open.session, new HashSet<>(Set.of("pv1", "pv2")));
    manager.addPvs(closed.session, new HashSet<>(Set.of("pv3")));
    closed.open = false;
    manager.send(open.session, "pv1", "queued");
    manager.send(open.session, "pv2", "dropped");

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

  private PvListener listenerOf(FakeSession client) {
    PvListener listener = manager.listenerMap.get(client.session);
    assertNotNull("No listener for " + client.session, listener);
    return listener;
  }

  /** Records subscriptions instead of creating channels. */
  private static class RecordingChannelManager extends ChannelManager {
    private final Map<PvListener, Set<String>> pvsByListener = new ConcurrentHashMap<>();
    private final List<PvListener> removedAll = new ArrayList<>();

    RecordingChannelManager() {
      super(null, null, null);
    }

    @Override
    public void addPv(PvListener listener, String pv) {
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
