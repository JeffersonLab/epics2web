package org.jlab.epics2web;

import com.cosylab.epics.caj.CAJChannel;
import com.cosylab.epics.caj.CAJContext;
import com.github.dockerjava.api.command.CreateContainerCmd;
import gov.aps.jca.CAException;
import gov.aps.jca.TimeoutException;
import gov.aps.jca.configuration.DefaultConfiguration;
import gov.aps.jca.dbr.DBR;
import gov.aps.jca.dbr.DBRType;
import gov.aps.jca.dbr.DBR_Double;
import gov.aps.jca.event.*;
import org.jlab.epics2web.epics.ChannelManager;
import org.jlab.epics2web.epics.ContextFactory;
import org.jlab.epics2web.epics.PvListener;
import org.junit.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.*;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class IntegrationErrorTest {
    private static ChannelManager channelManager;
    private static CAJContext context;
    private static ScheduledExecutorService timeoutExecutor;
    private static ExecutorService callbackExecutor;

    private static Logger LOGGER = LoggerFactory.getLogger(IntegrationErrorTest.class);

    @ClassRule
    public static Network network = Network.newNetwork();


    public static GenericContainer softioc = new GenericContainer("slominskir/softioc") {
        {
            this.addFixedExposedPort(5064, 5064, InternetProtocol.TCP);
            this.addFixedExposedPort(5065, 5065, InternetProtocol.TCP);
            this.addFixedExposedPort(5064, 5064, InternetProtocol.UDP);
            this.addFixedExposedPort(5065, 5065, InternetProtocol.UDP);
        }
    }
            //.withExposedPorts(5064, 5065)
            .withNetwork(network)
            .withPrivilegedMode(true)
            .withCreateContainerCmdModifier(new Consumer<CreateContainerCmd>() {
                @Override
                public void accept(CreateContainerCmd cmd) {
                    cmd
                            .withHostName("softioc") // Fixed hostname so we can stop/start and check if monitors automatically recover
                            .withUser("root")
                            .withAttachStdin(true)
                            .withStdinOpen(true)
                            .withTty(true);
                }
            })
            .withLogConsumer(new Slf4jLogConsumer(LOGGER))
            .waitingFor(Wait.forLogMessage("iocRun: All initialization complete", 1))
            //.withClasspathResourceMapping("softioc.db", "/db/softioc.db", BindMode.READ_ONLY)
            .withFileSystemBind("examples/softioc-db", "/db", BindMode.READ_ONLY);

    @BeforeClass
    public static void setUp() throws CAException {
        softioc.start();

        String hostname = softioc.getHost();
        //Integer port = softioc.getFirstMappedPort();

        // Set EPICS_CA_ADDR_LIST
        DefaultConfiguration config = ContextFactory.getDefault();
        config.setAttribute("addr_list", hostname);
        config.setAttribute("beacon_period", "0.5");
        config.setAttribute("connection_timeout", "0.5");

        ContextFactory factory = new ContextFactory(config);

        context = factory.newContext();

        context.printInfo(System.err);

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

    /**
     * This test simply creates a monitor, triggers a change via caput, then confirms that the monitor callback received
     * the initialization value (0) plus the caput value (1).
     *
     * @throws InterruptedException If a thread is interrupted
     * @throws IOException If unable to perform IO
     * @throws CAException If unable to communicate over CA
     */
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

    /**
     * This test creates a monitor on a channel hosted on the "softioc", then cleanly shuts the softioc down, waits a
     * few seconds, and starts it back up (restart IOC simulation), then checks if connection callback detected
     * disconnect followed by re-connect plus Context Exception callback should detect CA Disconnect code 24.
     *
     * @throws IOException If unable to perform IO
     * @throws InterruptedException If a thread is interrupted
     * @throws CAException If unable to communicate over CA
     */
    @Test
    public void testCleanRestartCode24() throws IOException, InterruptedException, CAException {
        final CountDownLatch latch = new CountDownLatch(2);
        final AtomicInteger code = new AtomicInteger();

        PvListener listener = new PvListener() {
            @Override
            public void notifyPvInfo(String pv, boolean couldConnect, DBRType type, Integer count, String[] enumLabels) {
                System.err.println("ConnectionListener: connected: " + couldConnect);
                if(couldConnect) {
                    latch.countDown();
                }
            }

            @Override
            public void notifyPvUpdate(String pv, DBR dbr) {
                // Do nothing - we don't care for this test
            }
        };

        // Log any exceptions and check for code 24
        context.addContextExceptionListener(new ContextExceptionListener() {
            @Override
            public void contextException(ContextExceptionEvent ev) {
                System.err.println("ContextException: " + ev);
            }

            @Override
            public void contextVirtualCircuitException(ContextVirtualCircuitExceptionEvent ev) {
                System.err.println("ContextVirtualCircuitException: Status: " + ev.getStatus() + ", IP: " + ev.getVirtualCircuit() + ", Source: ");
                ((CAJContext)ev.getSource()).printInfo(System.err);

                System.err.println("status: " + ev.getStatus());
                System.err.println("value: " + ev.getStatus().getValue()); // We want value, not code;  this is very confusing
                System.err.println("code: " + ev.getStatus().getStatusCode()); // 192?   What the heck?
                System.err.println("severity: " + ev.getStatus().getSeverity());

                code.set(ev.getStatus().getValue());
            }
        });

        context.addContextMessageListener(new ContextMessageListener() {
            @Override
            public void contextMessage(ContextMessageEvent ev) {
                System.err.println("ContextMessage: " + ev);
            }
        });

        channelManager.addPv(listener, "channel2");

        Thread.sleep(1000);

        softioc.stop(); // Clean stop should result in status 24 plus connection callback informs us when no longer connected!

        Thread.sleep(1000);

        softioc.start();

        latch.await(5, TimeUnit.SECONDS); // If this is too short and earlier sleep calls too long then final ConnectionListener update is not received!

        channelManager.removeListener(listener);

        Assert.assertEquals(0, latch.getCount());
        Assert.assertEquals(24, code.get());
    }

    /**
     * This test creates a monitor on a channel hosted on the "softioc", then severs the connection abruptly by
     * disabling the network interface on the softioc, waits for unresponsive timeout periods to elapse, then
     * restores the network interface; an error code 60 "unresponsive", should be detected.
     *
     * @throws IOException If unable to perform IO
     * @throws InterruptedException If a thread is interrupted
     * @throws CAException If unable to communicate over CA
     */
    //@Test // Oddly doesn't result in code 60 on GitHub Action
    public void testUnresponsiveCode60() throws IOException, InterruptedException, CAException {
        final CountDownLatch latch = new CountDownLatch(2);
        final AtomicInteger code = new AtomicInteger();

        PvListener listener = new LatchPvListener(latch);

        // TODO: Looks like lots of ways connections fail since so many different ports are being used to communicate over both UDP and TCP - lots of combinations of failure modes
        // https://epics.anl.gov/docs/APS2014/05-CA-Concepts.pdf
        // https://epics-controls.org/resources-and-support/documents/ca/

        // TODO: explore CAJ_DO_NOT_SHARE_CHANNELS option
        //context.setDoNotShareChannels(true);

        context.addContextExceptionListener(new ContextExceptionListener() {
            @Override
            public void contextException(ContextExceptionEvent ev) {
                System.err.println("ContextException: " + ev);
            }

            @Override
            public void contextVirtualCircuitException(ContextVirtualCircuitExceptionEvent ev) {
                System.err.println("ContextVirtualCircuitException: Status: " + ev.getStatus() + ", IP: " + ev.getVirtualCircuit() + ", Source: ");
                ((CAJContext)ev.getSource()).printInfo(System.err);
                code.set(ev.getStatus().getValue());
            }
        });

        context.addContextMessageListener(new ContextMessageListener() {
            @Override
            public void contextMessage(ContextMessageEvent ev) {
                System.err.println("ContextMessage: " + ev);
            }
        });

        channelManager.addPv(listener, "channel3");

        Thread.sleep(1000);

        Container.ExecResult result = softioc.execInContainer("ip", "link", "set", "eth0", "down");
        System.out.println("err: " + result.getStderr());
        System.out.println("out: " + result.getStdout());
        System.out.println("exit: " + result.getExitCode());

        Thread.sleep(5000);

        result = softioc.execInContainer("ip", "link", "set", "eth0", "up");
        System.out.println("err: " + result.getStderr());
        System.out.println("out: " + result.getStdout());
        System.out.println("exit: " + result.getExitCode());

        Thread.sleep(5000);

        latch.await(10, TimeUnit.SECONDS);

        channelManager.removeListener(listener);

        Assert.assertEquals(60, code.get());
    }

    /**
     * This test creates a monitor on a channel then performs a get request on that same channel.  Note that the
     * CAJChannel object is shared via internal JCA/CAJ lookup (CAJContext.createChannel returns existing CAJChannel if
     * it already exists for a given channel name and priority).
     *
     * @throws IOException If unable to perform IO
     * @throws InterruptedException If a thread is interrupted
     * @throws CAException If unable to communicate over CA
     */
    @Test
    public void testSharedChannel() throws IOException, InterruptedException, CAException, TimeoutException {
        final CountDownLatch latch = new CountDownLatch(2);
        final AtomicInteger code = new AtomicInteger();

        PvListener listener = new LatchPvListener(latch);

        context.addContextExceptionListener(new ContextExceptionListener() {
            @Override
            public void contextException(ContextExceptionEvent ev) {
                System.err.println("ContextException: " + ev);
            }

            @Override
            public void contextVirtualCircuitException(ContextVirtualCircuitExceptionEvent ev) {
                System.err.println("ContextVirtualCircuitException: Status: " + ev.getStatus() + ", IP: " + ev.getVirtualCircuit() + ", Source: ");
                ((CAJContext)ev.getSource()).printInfo(System.err);
                code.set(ev.getStatus().getValue());
            }
        });

        context.addContextMessageListener(new ContextMessageListener() {
            @Override
            public void contextMessage(ContextMessageEvent ev) {
                System.err.println("ContextMessage: " + ev);
            }
        });

        channelManager.addPv(listener, "channel3");

        Thread.sleep(1000);

        try {
            List<DBR> valueList = channelManager.get(new String[]{"channel3"}, false);

            System.out.println("Get Value: " + ((DBR_Double)valueList.get(0)).getDoubleValue()[0]);
        } catch(gov.aps.jca.TimeoutException e) {
            e.printStackTrace();
        }

        CAJChannel channel = (CAJChannel)context.createChannel("channel3", new ConnectionListener() {
            @Override
            public void connectionChanged(ConnectionEvent ev) {
                System.out.println("Custom ConnectionListener: Connected: " + ev.isConnected());
            }
        });

        context.pendIO(1000);

        double value = ((DBR_Double)channel.get()).getDoubleValue()[0];

        context.pendIO(1000);

        channel.destroyChannel(true); // Force ignores reference count and destroys channel even if others are using it!

        Thread.sleep(1000);

        latch.await(5, TimeUnit.SECONDS);




        IllegalStateException e = Assert.assertThrows(IllegalStateException.class, () -> channelManager.removeListener(listener));

        Assert.assertEquals("Channel already destroyed.", e.getMessage());

        Thread.sleep(1000);
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
