package org.jlab.epics2web;

import com.cosylab.epics.caj.CAJContext;
import com.github.dockerjava.api.command.CreateContainerCmd;
import gov.aps.jca.CAException;
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
import org.testcontainers.utility.DockerImageName;

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

    @ClassRule
    public static Network network = Network.newNetwork();

    @ClassRule
    public static ToxiproxyContainer toxiproxy = new ToxiproxyContainer("shopify/toxiproxy:2.1.0")
            .withNetwork(network)
            .withNetworkAliases("toxinet");

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


    final ToxiproxyContainer.ContainerProxy softproxy = toxiproxy.getProxy(softioc, 5064); // This isn't going to work - we need proxy to handle 2 TCP and 2 UDP ports!

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

    @Test
    public void testUnresponsiveCode60() throws IOException, InterruptedException, CAException {
        // for status 60 we might be able to use https://www.testcontainers.org/modules/toxiproxy/ for TCP pieces.  We would need something else for UDP, perhaps Linux "tc".

        final CountDownLatch latch = new CountDownLatch(2);

        PvListener listener = new LatchPvListener(latch);

        // TODO: Looks like lots of ways connections fail since so many different ports are being used to communicate over both UDP and TCP - lots of combinations of failure modes
        // https://epics.anl.gov/docs/APS2014/05-CA-Concepts.pdf
        // https://epics-controls.org/resources-and-support/documents/ca/

        // TODO: explore CAJ_DO_NOT_SHARE_CHANNELS option
        //context.setDoNotShareChannels(true);

        // TODO: explore beacon period and connection timeout - set to small value to speed up test?

        context.addContextExceptionListener(new ContextExceptionListener() {
            @Override
            public void contextException(ContextExceptionEvent ev) {
                System.err.println("ContextException: " + ev);
            }

            @Override
            public void contextVirtualCircuitException(ContextVirtualCircuitExceptionEvent ev) {
                System.out.println("ContextVirtualCircuitException: " + ev);
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

        Container.ExecResult result = softioc.execInContainer("iptables", "-A", "INPUT", "-p", "tcp", "--destination-port", "5065", "-j", "DROP");
        //Container.ExecResult result = softioc.execInContainer("ip", "link", "set", "eth0", "down");
        System.out.println("err: " + result.getStderr());
        System.out.println("out: " + result.getStdout());
        System.out.println("exit: " + result.getExitCode());

        softioc.stop(); // Clean stop not going to cut it... we need to abruptly sever connection...

        Thread.sleep(1000);

        softioc.start();

        /*Container.ExecResult result = softioc.execInContainer("caput", "channel3", "1");
        System.out.println("err: " + result.getStderr());
        System.out.println("out: " + result.getStdout());
        System.out.println("exit: " + result.getExitCode());*/

        latch.await(5, TimeUnit.SECONDS);

        channelManager.removeListener(listener);

        Assert.assertEquals(0, latch.getCount());
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
