package org.jlab.epics2web.websocket;

/**
 *
 * @author slominskir
 */
public enum WriteStrategy {
    CALLBACK_BLOCKER, // Just cause CAJ LeaderFollowerThreadPool (5 threads) thread to wait until write is complete (doesn't scale well / overload causes unresponsive transport due to not handling incoming Selector messages fast enough)
    BLOCKING_QUEUE, // One extra thread per Web Socket Session scales okay
    ASYNC_QUEUE; // Single writer threads reading from multiple session queues; scales well, but busy spin hogs entire CPU core

}
