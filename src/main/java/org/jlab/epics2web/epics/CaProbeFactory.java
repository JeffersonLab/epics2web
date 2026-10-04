package org.jlab.epics2web.epics;

import com.cosylab.epics.caj.CAJChannel;
import com.cosylab.epics.caj.CAJContext;
import gov.aps.jca.CAException;
import gov.aps.jca.Channel;
import gov.aps.jca.Monitor;
import gov.aps.jca.event.MonitorEvent;
import gov.aps.jca.event.MonitorListener;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Probes PVs for FrozenPvDetector with subscriptions in their own CA context, separate from the one
 * monitors use. Each subscription is made like ChannelMonitor's, so the IOC applies the same
 * deadband to both.
 */
public class CaProbeFactory implements FrozenPvDetector.ProbeFactory {

  private static final Logger LOGGER = Logger.getLogger(CaProbeFactory.class.getName());

  private final CAJContext context;

  /**
   * Create a new CaProbeFactory.
   *
   * @param context A CA context used only for probes
   */
  public CaProbeFactory(CAJContext context) {
    this.context = context;
  }

  @Override
  public FrozenPvDetector.Probe start(String pv) throws CAException {
    CAJChannel channel = (CAJChannel) context.createChannel(pv);
    context.flushIO();
    return new CaProbe(pv, channel);
  }

  private class CaProbe implements FrozenPvDetector.Probe, MonitorListener {
    private final String pv;
    private final CAJChannel channel;
    private final CountDownLatch firstUpdate = new CountDownLatch(1);
    private Monitor monitor; // Only used from the detector's thread
    private volatile Instant connectedAt;
    private volatile Instant firstChangeAt;

    private CaProbe(String pv, CAJChannel channel) {
      this.pv = pv;
      this.channel = channel;
    }

    /**
     * Subscribe once connected. CAJ's own callbacks mustn't call back into it, so it's done here.
     */
    @Override
    public void poll() {
      if (monitor == null && channel.getConnectionState() == Channel.ConnectionState.CONNECTED) {
        try {
          // As ChannelMonitor does: arrays aren't handled, except BYTE[] as a long string
          int count = 1;
          if (channel.getFieldType().isBYTE() && channel.getElementCount() > 1) {
            count = channel.getElementCount();
          }
          monitor = channel.addMonitor(channel.getFieldType(), count, Monitor.VALUE, this);
          context.flushIO();
        } catch (CAException | IllegalStateException e) {
          LOGGER.log(Level.FINE, "Unable to subscribe probe of " + pv, e);
        }
      }
    }

    @Override
    public void monitorChanged(MonitorEvent event) {
      if (event.getStatus() == null || !event.getStatus().isSuccessful()) {
        return;
      }
      Instant now = Instant.now();
      if (connectedAt == null) {
        connectedAt = now; // The initial value sent in reply to the subscription
        firstUpdate.countDown();
      } else if (firstChangeAt == null) {
        firstChangeAt = now;
      }
    }

    @Override
    public Instant connectedAt() {
      return connectedAt;
    }

    @Override
    public Instant firstChangeAt() {
      return firstChangeAt;
    }

    @Override
    public void close() {
      if (monitor != null) {
        // As in ChannelMonitor.close(): CAJ sends a cancel at once but queues the add, so let the
        // IOC confirm the subscription first, or it may get the cancel first and drop the circuit
        try {
          if (channel.getConnectionState() == Channel.ConnectionState.CONNECTED) {
            firstUpdate.await(ChannelMonitor.TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        try {
          monitor.clear();
        } catch (CAException | IllegalStateException e) {
          LOGGER.log(Level.FINE, "Unable to clear probe of " + pv, e);
        }
      }
      try {
        context.destroyChannel(channel, false);
      } catch (CAException | IllegalStateException e) {
        LOGGER.log(Level.FINE, "Unable to destroy probe channel of " + pv, e);
      }
    }
  }
}
