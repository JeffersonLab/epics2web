package org.jlab.epics2web;

import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

/**
 *
 * @author slominskir
 */
public class CustomPrefixThreadFactory implements ThreadFactory {

    private final ThreadFactory threadFactory;
    private final String prefix;
    private final AtomicLong index = new AtomicLong();

    public CustomPrefixThreadFactory(final String prefix) {
        this(Executors.defaultThreadFactory(), prefix);
    }
    
    public CustomPrefixThreadFactory(final ThreadFactory threadFactory, final String prefix) {
        this.threadFactory = threadFactory;
        this.prefix = prefix;
    }

    @Override
    public Thread newThread(final Runnable r) {
        final Thread thread = threadFactory.newThread(r);
        thread.setName(prefix + index.getAndIncrement());
        return thread;
    }
}
