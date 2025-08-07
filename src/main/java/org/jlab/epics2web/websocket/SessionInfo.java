package org.jlab.epics2web.websocket;

/**
 * This class provides a snapshot of websocket information that will not throw Exceptions if you try to interrogate it
 * after the session happens to have closed.  There is a race condition if you hand a list of jakarta.websocket.Session
 * objects to a debug console for example as if the session happens to close between the time you return it and the
 * time the debug console calls the userProperties method for example you get an exception.
 */
public class SessionInfo {
    private String id;
    private String ip;
    private String name;
    private String agent;
    private long droppedMessageCount;

    public SessionInfo(String id, String ip, String name, String agent, long droppedMessageCount) {
        this.id = id;
        this.ip = ip;
        this.name = name;
        this.agent = agent;
        this.droppedMessageCount = droppedMessageCount;
    }

    public String getId() {
        return id;
    }

    public String getIp() {
        return ip;
    }

    public String getName() {
        return name;
    }

    public String getAgent() {
        return agent;
    }

    public long getDroppedMessageCount() {
        return droppedMessageCount;
    }
}
