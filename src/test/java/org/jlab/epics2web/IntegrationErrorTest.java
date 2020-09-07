package org.jlab.epics2web;

import com.cosylab.epics.caj.CAJChannel;
import com.cosylab.epics.caj.CAJContext;
import com.github.dockerjava.api.command.CreateContainerCmd;
import gov.aps.jca.CAException;
import gov.aps.jca.configuration.DefaultConfiguration;
import gov.aps.jca.dbr.DBR;
import gov.aps.jca.dbr.DBRType;
import gov.aps.jca.dbr.DBR_Double;
import gov.aps.jca.event.ConnectionEvent;
import gov.aps.jca.event.ConnectionListener;
import org.jlab.epics2web.epics.ChannelManager;
import org.jlab.epics2web.epics.ContextFactory;
import org.jlab.epics2web.epics.PvListener;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.InternetProtocol;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class IntegrationErrorTest {
    private static ChannelManager channelManager;
    private static CAJContext context;
    private static ScheduledExecutorService timeoutExecutor;
    private static ExecutorService callbackExecutor;

    private static Logger LOGGER = LoggerFactory.getLogger(IntegrationErrorTest.class);

    public static GenericContainer softioc = new GenericContainer("slominskir/softioc") {
        {
            this.addFixedExposedPort(5064, 5064, InternetProtocol.TCP);
            this.addFixedExposedPort(5065, 5065, InternetProtocol.TCP);
            this.addFixedExposedPort(5064, 5064, InternetProtocol.UDP);
            this.addFixedExposedPort(5065, 5065, InternetProtocol.UDP);
        }
    }
            //.withExposedPorts(5064, 5065)
            .withClasspathResourceMapping("softioc.db",
                    "/db/softioc.db",
                    BindMode.READ_ONLY).withLogConsumer(new Slf4jLogConsumer(LOGGER))
            .withCreateContainerCmdModifier(new Consumer<CreateContainerCmd>() {
                @Override
                public void accept(CreateContainerCmd cmd) {
                    cmd
                            .withAttachStdin(true)
                            .withStdinOpen(true)
                            .withTty(true);
                }
            })
            .waitingFor(Wait.forLogMessage("iocRun: All initialization complete", 1));

    @BeforeClass
    public static void setUp() throws CAException {
        softioc.start();

        String hostname = softioc.getHost();
        //Integer port = softioc.getFirstMappedPort();

        // Set EPICS_CA_ADDR_LIST
        DefaultConfiguration config = ContextFactory.getDefault();
        config.setAttribute("addr_list", hostname);

        ContextFactory factory = new ContextFactory(config);

        context = factory.newContext();

        timeoutExecutor = Executors.newScheduledThreadPool(1, new CustomPrefixThreadFactory("CA-Timeout-"));
        callbackExecutor = Executors.newCachedThreadPool(new CustomPrefixThreadFactory("Callback-"));

        channelManager = new ChannelManager(context, timeoutExecutor, callbackExecutor);
    }

    @AfterClass
    public static void tearDown() {
        if(context != null) {
            try {
                context.destroy();
            } catch(Exception e) {
                LOGGER.warn("Unable to cleanup CA Context", e);
            }
        }

        softioc.stop();

        timeoutExecutor.shutdownNow();
        callbackExecutor.shutdownNow();
    }

    @Test
    public void testBasicMonitor() throws InterruptedException, IOException, CAException {
        final CountDownLatch latch = new CountDownLatch(2);

        PvListener listener = new LatchPvListener(latch);

        channelManager.addPv(listener, "channel1");

        Container.ExecResult result = softioc.execInContainer("caput", "channel1", "1");
        System.out.println("err: " + result.getStderr());
        System.out.println("out: " + result.getStdout());
        System.out.println("exit: " + result.getExitCode());

        latch.await(5, TimeUnit.SECONDS);

        channelManager.removeListener(listener);

        Assert.assertEquals(0, latch.getCount());
    }

    @Test
    public void testCAStatus24() throws IOException, InterruptedException, CAException {
        // We really can't stop, then start container without setting fixed hostname!  Not sure where this test is going... yet!

        final CountDownLatch latch = new CountDownLatch(2);

        PvListener listener = new LatchPvListener(latch);

        channelManager.addPv(listener, "channel2");

        // Second execInContainer causes indefinite hang.... weird.  One more issue to troubleshoot; separate test class might be required?

        /*Container.ExecResult result = softioc.execInContainer("caput", "channel2", "1");
        System.out.println("err: " + result.getStderr());
        System.out.println("out: " + result.getStdout());
        System.out.println("exit: " + result.getExitCode());*/

        latch.await(5, TimeUnit.SECONDS);

        channelManager.removeListener(listener);

        //Assert.assertEquals(0, latch.getCount());
    }

    /**
     * PvListener for Testing that simply logs updates/info from monitor and counts down the provided latch on updates.
     *
     * This listener assumes it is listening to scalar double typed data (like from the test softioc)
     * */
    private class LatchPvListener implements PvListener {
        private CountDownLatch latch;

        LatchPvListener(CountDownLatch latch) {
            this.latch = latch;
        }

        @Override
        public void notifyPvInfo(String pv, boolean couldConnect, DBRType type, Integer count, String[] enumLabels) {
            System.out.println("Info: " + pv + " " + couldConnect);
        }

        @Override
        public void notifyPvUpdate(String pv, DBR dbr) {
            System.out.println("Update: " + pv + " " + ((DBR_Double)dbr).getDoubleValue()[0]);
            latch.countDown();
        }
    }
}
