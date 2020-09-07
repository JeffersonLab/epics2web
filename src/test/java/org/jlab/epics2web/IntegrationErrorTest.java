package org.jlab.epics2web;

import com.cosylab.epics.caj.CAJContext;
import gov.aps.jca.CAException;
import gov.aps.jca.dbr.DBR;
import gov.aps.jca.dbr.DBRType;
import org.jlab.epics2web.epics.ChannelManager;
import org.jlab.epics2web.epics.ContextFactory;
import org.jlab.epics2web.epics.PvListener;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.testcontainers.containers.GenericContainer;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

public class IntegrationErrorTest {
    private static ChannelManager channelManager;
    private static CAJContext context;

    public static GenericContainer softioc = new GenericContainer("slominskir/softioc")
            .withExposedPorts(5064, 5065);

    @BeforeClass
    public static void setUp() throws CAException {
        softioc.start();

        String address = softioc.getHost();
        Integer port = softioc.getFirstMappedPort();

        // Set EPICS_CA_ADDR_LIST somehow...

        ContextFactory factory = new ContextFactory();

        CAJContext context = factory.newContext();

        ScheduledExecutorService timeoutExecutor = Executors.newScheduledThreadPool(1, new CustomPrefixThreadFactory("CA-Timeout-"));
        ExecutorService callbackExecutor = Executors.newCachedThreadPool(new CustomPrefixThreadFactory("Callback-"));

        channelManager = new ChannelManager(context, timeoutExecutor, callbackExecutor);
    }

    @AfterClass
    public static void tearDown() throws CAException {
        context.destroy();

        softioc.stop();
    }

    @Test
    public void testCAStatus24() {
        channelManager.addPv(new PvListener() {
            @Override
            public void notifyPvInfo(String pv, boolean couldConnect, DBRType type, Integer count, String[] enumLabels) {

            }

            @Override
            public void notifyPvUpdate(String pv, DBR dbr) {

            }
        }, "channel1");

        System.out.println("Hello World");
    }
}
