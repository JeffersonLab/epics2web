package org.jlab.epics2web.websocket;

import java.util.List;
import java.util.Map;

/**
 * Stores the request headers and remote address of clients. This is necessary since the Java web
 * socket API currently does not expose this information. This is somewhat dangerous from a
 * class-loader leak perspective as it is using ThreadLocal.
 *
 * @author ryans
 */
public class WebSocketAuditContext {

    private final Map<String, List<String>> headers;
    private final String remoteAddr;

    /**
     * Create a new WebSocketAuditContext.
     *
     * @param headers The request headers
     * @param remoteAddr The remote address
     */
    public WebSocketAuditContext(Map<String, List<String>> headers, String remoteAddr) {
        this.headers = headers;
        this.remoteAddr = remoteAddr;
    }

    /**
     * Return the request headers.
     *
     * @return The headers
     */
    public Map<String, List<String>> getHeaders() {
        return headers;
    }

    /**
     * Return the remote address.
     *
     * @return The remote address
     */
    public String getRemoteAddr() {
        return remoteAddr;
    }

    /**
     * The ThreadLocal instance.
     */
    private static final ThreadLocal<WebSocketAuditContext> INSTANCE
            = new ThreadLocal<WebSocketAuditContext>() {

        @Override
        protected WebSocketAuditContext initialValue() {
            return null;
        }
    };

    /**
     * Return the audit context instance.
     * 
     * @return The audit context instance
     */
    public static WebSocketAuditContext getCurrentInstance() {
        return INSTANCE.get();
    }

    /**
     * Set the current audit context instance.
     * 
     * @param context The audit context instance
     */
    public static void setCurrentInstance(WebSocketAuditContext context) {
        if (context == null) {
            INSTANCE.remove();
        } else {
            INSTANCE.set(context);
        }
    }
}
