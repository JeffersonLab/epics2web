package org.jlab.epics2web.websocket;

import java.util.List;
import java.util.Map;
import jakarta.servlet.http.HttpSession;
import jakarta.websocket.HandshakeResponse;
import jakarta.websocket.server.HandshakeRequest;
import jakarta.websocket.server.ServerEndpointConfig;

/**
 * Web socket configuration class. This is necessary to obtain connection headers and remote address
 * of clients due to deficiencies in Java web socket API specification.
 *
 * @author slominskir
 */
public class AuditServerEndpointConfigurator extends ServerEndpointConfig.Configurator {

    @Override
    public void modifyHandshake(ServerEndpointConfig config, HandshakeRequest request,
            HandshakeResponse response) {

        Map<String, List<String>> headers = request.getHeaders();
        String remoteAddr = (String) ((HttpSession) request.getHttpSession()).getAttribute(
                "remoteAddr");

        // We don't use config.getUserProperties.add because it isn't one-to-one with a web socket connection
        WebSocketAuditContext.setCurrentInstance(new WebSocketAuditContext(headers, remoteAddr));
    }
}
