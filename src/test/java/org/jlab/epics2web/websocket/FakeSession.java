package org.jlab.epics2web.websocket;

import jakarta.websocket.Session;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A WebSocket session for tests, set up the way MonitorEndpoint.onOpen sets up a real one for the
 * BLOCKING_QUEUE write strategy. Messages sent to it collect in its write queue. Only the Session
 * methods WebSocketSessionManager uses are supported.
 */
class FakeSession {

  final ArrayBlockingQueue<String> writeQueue;
  final Map<String, Object> userProperties = new ConcurrentHashMap<>();
  final Session session;
  volatile boolean open = true;

  FakeSession(String id, int queueSize) {
    writeQueue = new ArrayBlockingQueue<>(queueSize);
    userProperties.put("writequeue", writeQueue);
    userProperties.put("droppedMessageCount", new AtomicLong());

    session =
        (Session)
            Proxy.newProxyInstance(
                Session.class.getClassLoader(),
                new Class<?>[] {Session.class},
                (proxy, method, args) ->
                    switch (method.getName()) {
                      case "getId" -> id;
                      case "isOpen" -> open;
                      case "getUserProperties" -> userProperties;
                      case "hashCode" -> System.identityHashCode(proxy);
                      case "equals" -> proxy == args[0];
                      case "toString" -> "FakeSession " + id;
                      default -> throw new UnsupportedOperationException(method.getName());
                    });
  }

  FakeSession(String id) {
    this(id, 100);
  }

  long droppedMessageCount() {
    return ((AtomicLong) userProperties.get("droppedMessageCount")).get();
  }
}
