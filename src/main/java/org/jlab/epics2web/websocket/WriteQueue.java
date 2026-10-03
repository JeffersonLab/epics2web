package org.jlab.epics2web.websocket;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Messages waiting to be written to one WebSocket session, in order.
 *
 * <p>A client that falls behind should get each PV's latest value, not a backlog of old ones. So an
 * update for a PV that already has an update waiting replaces that update's message in place,
 * unless an info message for the PV was queued after it: an update must not be moved ahead of an
 * info message for its PV, or a client could be left showing a value from before a disconnect.
 *
 * <p>Info messages are never dropped. Updates for PVs with nothing waiting, and other messages, are
 * dropped once the queue holds sizeLimit messages; with updates merged, that happens only if the
 * client monitors about that many PVs.
 */
public class WriteQueue {

  private static class Entry {
    private final String pv;
    private String msg;

    private Entry(String pv, String msg) {
      this.pv = pv;
      this.msg = msg;
    }
  }

  private final int sizeLimit;
  private final Deque<Entry> entries = new ArrayDeque<>();

  /** The waiting update for each PV that a newer update may still replace. */
  private final Map<String, Entry> mergeableUpdates = new HashMap<>();

  private final ReentrantLock lock = new ReentrantLock();
  private final Condition notEmpty = lock.newCondition();

  private boolean closed;

  /**
   * Create a new WriteQueue.
   *
   * @param sizeLimit The number of messages above which updates and other messages are dropped
   */
  public WriteQueue(int sizeLimit) {
    this.sizeLimit = sizeLimit;
  }

  /**
   * Add a PV update, replacing the PV's waiting update if it has one.
   *
   * @param pv The PV
   * @param msg The message
   * @return false if the message was dropped because the queue is full
   */
  public boolean offerUpdate(String pv, String msg) {
    lock.lock();
    try {
      if (closed) {
        return true;
      }

      Entry waiting = mergeableUpdates.get(pv);
      if (waiting != null) {
        waiting.msg = msg;
        return true;
      }

      if (entries.size() >= sizeLimit) {
        return false;
      }

      Entry entry = new Entry(pv, msg);
      mergeableUpdates.put(pv, entry);
      append(entry);
      return true;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Add a PV info message. These are never dropped, and later updates for the PV queue after it.
   *
   * @param pv The PV
   * @param msg The message
   */
  public void offerInfo(String pv, String msg) {
    lock.lock();
    try {
      if (closed) {
        return;
      }

      mergeableUpdates.remove(pv);
      append(new Entry(null, msg));
    } finally {
      lock.unlock();
    }
  }

  /**
   * Add a message that isn't about a PV, such as a pong.
   *
   * @param msg The message
   * @return false if the message was dropped because the queue is full
   */
  public boolean offer(String msg) {
    lock.lock();
    try {
      if (closed) {
        return true;
      }

      if (entries.size() >= sizeLimit) {
        return false;
      }

      append(new Entry(null, msg));
      return true;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Remove the next message, waiting until there is one or the queue is closed.
   *
   * @return The message, or null once the queue is closed
   * @throws InterruptedException If interrupted while waiting
   */
  public String take() throws InterruptedException {
    lock.lockInterruptibly();
    try {
      while (entries.isEmpty() && !closed) {
        notEmpty.await();
      }
      return closed ? null : remove();
    } finally {
      lock.unlock();
    }
  }

  /**
   * Remove the next message, if there is one.
   *
   * @return The message, or null if the queue is empty
   */
  public String poll() {
    lock.lock();
    try {
      return entries.isEmpty() ? null : remove();
    } finally {
      lock.unlock();
    }
  }

  /**
   * Discard waiting messages and ignore later ones, and make a waiting take return null. This stops
   * a session's writer thread without interrupting it: an interrupted thread can't destroy Channel
   * Access channels, and the writer thread may be the one closing the session.
   */
  public void close() {
    lock.lock();
    try {
      closed = true;
      entries.clear();
      mergeableUpdates.clear();
      notEmpty.signalAll();
    } finally {
      lock.unlock();
    }
  }

  /**
   * The number of messages waiting.
   *
   * @return The size
   */
  public int size() {
    lock.lock();
    try {
      return entries.size();
    } finally {
      lock.unlock();
    }
  }

  private void append(Entry entry) {
    entries.addLast(entry);
    notEmpty.signal();
  }

  private String remove() {
    Entry entry = entries.removeFirst();
    if (entry.pv != null) {
      mergeableUpdates.remove(entry.pv, entry);
    }
    return entry.msg;
  }
}
