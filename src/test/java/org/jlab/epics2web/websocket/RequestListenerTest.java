package org.jlab.epics2web.websocket;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletRequestEvent;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

public class RequestListenerTest {

  /** Session attributes, or null if no session was created. */
  private Map<String, Object> session;

  @Test
  public void webSocketHandshakeGetsSessionWithRemoteAddress() {
    requestInitialized("websocket");

    assertEquals(Map.of("remoteAddr", "10.0.0.1"), session);
  }

  @Test
  public void upgradeHeaderIsCaseInsensitive() {
    requestInitialized("WebSocket");

    assertEquals("10.0.0.1", session.get("remoteAddr"));
  }

  /** Other requests, such as /caget from a script without cookies, must not create sessions. */
  @Test
  public void otherRequestsGetNoSession() {
    requestInitialized(null);
    assertNull(session);

    requestInitialized("h2c");
    assertNull(session);
  }

  private void requestInitialized(String upgradeHeader) {
    session = null;
    HttpSession httpSession =
        proxy(
            HttpSession.class,
            (method, args) -> {
              if (method.equals("setAttribute")) {
                session.put((String) args[0], args[1]);
                return null;
              }
              throw new UnsupportedOperationException(method);
            });
    HttpServletRequest request =
        proxy(
            HttpServletRequest.class,
            (method, args) ->
                switch (method) {
                  case "getHeader" ->
                      "Upgrade".equalsIgnoreCase((String) args[0]) ? upgradeHeader : null;
                  case "getRemoteAddr" -> "10.0.0.1";
                  case "getSession" -> {
                    if (session == null) {
                      session = new HashMap<>();
                    }
                    yield httpSession;
                  }
                  default -> throw new UnsupportedOperationException(method);
                });
    ServletContext context =
        proxy(
            ServletContext.class,
            (method, args) -> {
              throw new UnsupportedOperationException(method);
            });

    new RequestListener().requestInitialized(new ServletRequestEvent(context, request));
  }

  private interface Handler {
    Object handle(String method, Object[] args);
  }

  @SuppressWarnings("unchecked")
  private static <T> T proxy(Class<T> type, Handler handler) {
    return (T)
        Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[] {type},
            (p, method, args) ->
                switch (method.getName()) {
                  case "hashCode" -> System.identityHashCode(p);
                  case "equals" -> p == args[0];
                  case "toString" -> type.getSimpleName();
                  default -> handler.handle(method.getName(), args);
                });
  }
}
