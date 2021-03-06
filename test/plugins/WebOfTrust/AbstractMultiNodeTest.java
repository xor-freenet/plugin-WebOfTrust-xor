/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import static java.lang.Math.max;
import static java.lang.Math.round;
import static java.lang.Thread.sleep;
import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.MalformedURLException;
import java.net.SocketException;
import java.util.ArrayList;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;

import plugins.WebOfTrust.exceptions.UnknownIdentityException;
import plugins.WebOfTrust.util.StopWatch;
import freenet.crypt.RandomSource;
import freenet.io.comm.PeerParseException;
import freenet.io.comm.ReferenceSignatureVerificationException;
import freenet.keys.FreenetURI;
import freenet.node.DarknetPeerNode.FRIEND_TRUST;
import freenet.node.DarknetPeerNode.FRIEND_VISIBILITY;
import freenet.node.FSParseException;
import freenet.node.Location;
import freenet.node.Node;
import freenet.node.NodeInitException;
import freenet.node.NodeStarter;
import freenet.node.NodeStarter.TestNodeParameters;
import freenet.node.NodeStats;
import freenet.node.PeerTooOldException;
import freenet.node.simulator.RealNodeRequestInsertTest;
import freenet.node.simulator.RealNodeTest;
import freenet.pluginmanager.PluginInfoWrapper;
import freenet.pluginmanager.PluginRespirator;
import freenet.support.Logger.LogLevel;
import freenet.support.LoggerHook.InvalidThresholdException;
import freenet.support.PooledExecutor;

/**
 * A base class for WoT unit tests.
 * As opposed to regular WoT unit tests based upon {@link AbstractJUnit4BaseTest}, this test runs
 * the unit tests inside one or multiple full Freenet nodes:
 * WoT is loaded as a regular plugin instead of executing the tests directly without Freenet.
 * 
 * This has the advantage of allowing more complex tests:
 * - The {@link PluginRespirator} is available.
 * - FCP can be used.
 * - Real network traffic can happen if more than one node is used.
 *   ATTENTION: If your {@link #shouldTerminateAllWoTThreads()} implementation returns true, then
 *   this class' {@link #loadWoT(Node)} stops all of WoT's networking threads to ensure tests don't
 *   have to deal with concurrency. To issue network traffic you then have to manually call their
 *   functions for uploading/downloading stuff.
 *   ATTENTION: Due to the Node's disability to be terminated without killing the whole JVM
 *   (see https://bugs.freenetproject.org/view.php?id=6683) this doesn't contain @After code to
 *   terminate the nodes. As Ant's JUnit will not terminate the JVM when running multiple @Tests of
 *   a single test class for tests which create many nodes you should typically only include a
 *   single test in one child class of this to prevent the nodes of the previous tests from slowing
 *   down the current one.
 *   If you because of this do create multiple classes which would actually belong into a single one
 *   please file bug to merge them once the said issue is resolved and set it as related to the
 *   issue so whoever fixes it does notice.
 * 
 * The price is that it is much more heavy to initialize and thus has a higher execution time.
 * Thus, please only use it as a base class if what {@link AbstractJUnit4BaseTest} provides is not
 * sufficient.
 * 
 * @see IdentityFetcherTest A simple example of how to use this in multi-node mode.
 * @see SubscriptionManagerFCPTest An example of how to use this in single-node mode. */
@Ignore("Is ignored so it can be abstract. Self-tests are at class AbstractMultiNodeTestSelfTest.")
public abstract class AbstractMultiNodeTest
        extends AbstractJUnit4BaseTest {

    /** Path of the WoT plugin JAR which will be loaded into the test's nodes. */
    public static final String WOT_JAR_FILE = System.getProperty("WOT_test_jar");

    static {
        assertNotNull("Please specify the path of the WOT unit test JAR to the JVM via "
            + "'java -DWOT_test_jar=...'",  WOT_JAR_FILE);
    }

    /**
     * We generate an ideal topology, no swapping needed.
     * 
     * This constant is not intended to be used, it really shouldn't exist and be hardcoded at our
     * {@link #setUpNode()} instead. But it's needed to make the flag accessible in a different
     * place of this class due to {@link Node#start(boolean)} wanting the value again even though we
     * already told it by {@link TestNodeParameters}. */
    private static final boolean ENABLE_SWAPPING = false;

    /** Needed for calling {@link NodeStarter#globalTestInit(File, boolean, LogLevel, String,
     *  boolean, RandomSource)} only once per VM as it requires that. */
    private static boolean sGlobalTestInitDone = false;

    private Node[] mNodes;

	/**
	 * We use one Executor for all Nodes we create so they can share the thread pool and we thus
	 * avoid having to create very many excess threads.
	 * This is inspired by fred's {@link RealNodeRequestInsertTest} as of fred build01478: it does
	 * that as well so it probably is OK to do. */
	private final PooledExecutor mExecutor = new PooledExecutor();

	/**
	 * Thread limit which is given to each Node.
	 * A single node as of build01478 will have about 64 threads when idle. We arbitrarily assume
	 * it needs half of that more if under load, which is 96.
	 * All nodes share the executor {@link #mExecutor} so multiply the expected minimal thread count
	 * for one node by their amount, and divide it by the arbitrary value of 2 to compensate for
	 * the fact that each node can use the unused threads of all other nodes. */
	private final int mThreadLimit = max(96, 96 * getNodeCount() / 2);


    /**
     * Implementing child classes shall make this return the desired amount of nodes which
     * AbstractMultiNodeTest will create at startup and load the WoT plugin into.
     * 
     * If you want to do networking, i.e. use a value greater than 1, be aware that the node
     * configuration settings which this class uses have been chosen and tested with a value of 100
     * nodes. */
    public abstract int getNodeCount();

    /**
     * How many instances of the WoT plugin to load into the nodes.
     * The first node of {@link #getNodes()} will receive the first instance, the second will get
     * the second, and so on.
     * Can be used if you want to have a high {@link #getNodeCount()} for a better network topology
     * but only need a few WoT instances. */
    public abstract int getWoTCount();

    /**
     * Implementations shall return true if the instances of the WoT plugin which are loaded into
     * the nodes shall have all threads stopped by {@link WebOfTrust#terminateSubsystemThreads()}
     * before running tests to allow the tests to not have any concurrency measures. */
    public abstract boolean shouldTerminateAllWoTThreads();

	/**
	 * Child classes can override this to set a detailed log level to override the default
	 * {@link LogLevel#WARNING} which this class passes to the "logThreshold" parameter of
	 * {@link NodeStarter#globalTestInit(File, boolean, LogLevel, String, boolean, RandomSource)}.
	 * For example:
	 *     "freenet.SOME_PACKAGE:NONE,plugins.WebOfTrust.SOME_CLASS:DEBUG"
	 * 
	 * Notice that this is passed as the "details" parameter to the function, i.e. is a secondary
	 * parameter in addition to the logThreshold parameter.
	 * Defaults to "freenet:NONE".
	 * 
	 * As a result by default WARNING and ERROR messages are logged of all classes of WoT, and no
	 * messages are logged of the fred core. */
	public String getDetailedLogLevel() {
		return "freenet:NONE";
	}

    @Before public final void setUpNodes()
            throws NodeInitException, InvalidThresholdException, IOException, FSParseException,
                   PeerParseException, ReferenceSignatureVerificationException, PeerTooOldException,
                   InterruptedException {
        
        System.out.println("AbstractMultiNodeTest: setUpNodes()...");
        StopWatch totalTime = new StopWatch();
        
        System.out.println("AbstractMultiNodeTest: Creating Nodes...");
        StopWatch time = new StopWatch();
        mNodes = new Node[getNodeCount()];
        for(int i = 0; i < mNodes.length; ++i)
        	mNodes[i] = setUpNode();
        System.out.println("AbstractMultiNodeTest: Creating Nodes done! Time: " + time);
        
        if(mNodes.length > 1)
            connectNodes();
        
        System.out.println("AbstractMultiNodeTest: Loading WoT into Nodes...");
        time = new StopWatch();
        assertTrue(getWoTCount() <= mNodes.length);
        for(int i = 0; i < getWoTCount(); ++i)
            loadWoT(mNodes[i]);
        System.out.println("AbstractMultiNodeTest: Loading WoT into Nodes done. Time: " + time);
        
        System.out.println("AbstractMultiNodeTest: setUpNodes() done! Time: " + totalTime);
    }

    private final Node setUpNode()
            throws NodeInitException, InvalidThresholdException, IOException {
        
        // TODO: As of 2014-09-30, TestNodeParameters does not provide any defaults, so we have to
        // set all of its values to something reasonable. Please check back whether it supports
        // defaults in the future and use them.
        TestNodeParameters params = new TestNodeParameters();
        // TODO: Also set a random TCP port for FCP
        ArrayList<Integer> ports = getFreeUDPPorts(2);
        params.port = ports.get(0);
        params.opennetPort = ports.get(1);
        params.baseDirectory = mTempFolder.newFolder();
        params.disableProbabilisticHTLs = false;
        params.maxHTL = 5; // From RealNodeRequestInsertTest of fred build01478, for 100 nodes.
        params.dropProb = 0;
        params.random = mRandom;
        params.executor = mExecutor;
        params.threadLimit = mThreadLimit;
        params.storeSize = 16 * 1024 * 1024; // Is not preallocated so a high value doesn't hurt
        params.ramStore = true;
        params.enableSwapping = ENABLE_SWAPPING;
        params.enableARKs = false; // We only connect the nodes locally, address lookup not needed
        params.enableULPRs = true;
        params.enablePerNodeFailureTables = false; // l10n says cooldown is 10m -> too long -> Off
        params.enableSwapQueueing = ENABLE_SWAPPING;
        params.enablePacketCoalescing = true; // On to keep CPU load lower as that's our bottleneck
        params.outputBandwidthLimit = 0; // = (Almost) unlimited, see NodeStarter.createTestNode()
        params.enableFOAF = true;
        params.connectToSeednodes = false; // We will only create a small darknet of our local nodes
        params.longPingTimes = true;
        params.useSlashdotCache = false; // Cannot be configured to be RAM-only so disable it.
        params.ipAddressOverride = null;
        params.enableFCP = true; // WoT has FCP
        params.enablePlugins = true;

        if(!sGlobalTestInitDone) {
            // NodeStarter.createTestNode() will throw if we do not call globalTestInit() before.
            //
            // NOTICE: We intentionally violate what the JavaDoc of globalTestInit() requests:
            // The directory we pass as baseDirectory is NOT passed as TestNodeParameters.params
            // to all of our nodes. In fact it isn't used at all.
            // This has to be done because JUnit's TemporaryFolder class will delete the temporary
            // folders between execution of each @Test, but the static fields of this class will
            // stay as is as the classloader isn't re-instantiated between test executions - so if
            // we wanted to store the baseDirectory in a static field so we can truly re-use it for
            // all other @Tests of the same VM as globalTestInit() demands it then that wouldn't be
            // possible.
            // The way to go is adding a new globalTestInit() to NodeStarter which does NOT require
            // passing a directory - the current implementation as of fred build01478 doesn't use
            // the directory for anything anyway, it doesn't even store it in a variable. The
            // current demand to use this directory as a base directory where all the nodes are
            // subdirs is likely only for beauty, it doesn't seem to serve a practical purpose.
            // Perhaps ask toad_ about it for safety nevertheless, i.e. ask whether a node will
            // somehow need to be able to interfere with the other nodes in the baseDirectory.
            // This has been documented at: https://bugs.freenetproject.org/view.php?id=6957
            NodeStarter.globalTestInit(mTempFolder.newFolder(), false, LogLevel.WARNING,
                getDetailedLogLevel(),
                true /* Disable DNS because we will only connect our nodes locally */,
                mRandom);
            
            sGlobalTestInitDone = true;
        }
        
        // Don't call Node.start() yet, we do it after creating darknet connections instead.
        // - That's how RealNodeRequestInsertTest does it.
        return NodeStarter.createTestNode(params);
    }


	/**
	 * TODO: Code quality: Move to {@link TestNodeParameters} and make it allocate node ports (and
	 * FCP ports if enabled!) automatically if the user chooses none (as indicated by choosing a
	 * port of 0, which in networking usually means to auto allocate one). */
	private final ArrayList<Integer> getFreeUDPPorts(int amount) {
		ArrayList<Integer> result = new ArrayList<>(amount);
		do {
			int candidate = getFreeUDPPort();
			// Avoid returning the same port twice.
			// TODO: Code quality: Use ArraySet once we have one.
			if(!result.contains(candidate))
				result.add(candidate);
		} while(result.size() != amount);
		return result;
	}

	private final int getFreeUDPPort() {
		while(true) {
			int port = mRandom.nextInt((65535 - 1024) + 1) + 1024;
			DatagramSocket socket = null;
			try {
				socket = new DatagramSocket(port);
				return port;
			} catch(SocketException e) {
				// Not free, try next one.
			} finally {
				if(socket != null)
					socket.close();
			}
		}
	}

    private final void loadWoT(Node node) {
        PluginInfoWrapper wotWrapper = 
            node.getPluginManager().startPluginFile(WOT_JAR_FILE, false);
        
        // startPluginFile() will NOT throw if starting the plugin fails, i.e. if
        // WebOftrust.runPlugin() throws an exception.
        // Thus we use this hack to detect that.
        // TODO: Code quality:
        // - Add a throwing startPlugin...() to fred and use it.
        // - Add JavaDoc to startPluginFile() (and potentially other startPlugin...()) to document
        //   that they don't throw.
        assertTrue(node.getPluginManager().isPluginLoaded(wotWrapper.getPluginClassName()));
        
        WebOfTrust wot = (WebOfTrust) wotWrapper.getPlugin();
        
        // Prevent unit tests from having to do thread synchronization by terminating all WOT
        // subsystems which run their own thread.
        if(shouldTerminateAllWoTThreads())
            wot.terminateSubsystemThreads();
    }

    public final Node getNode() {
        if(mNodes.length > 1)
            throw new UnsupportedOperationException("Running more than one Node!");
        
        return mNodes[0];
    }

    public final Node[] getNodes() {
        return mNodes;
    }

	/**
	 * Can be used to assess the health of the simulated network:
	 * - is the thread limit high enough?
	 * - are nodes signaling overload by marking a large percentage of their peers as backed off?
	 * - is the ping time of the nodes sufficiently low?
	 * 
	 * TODO: Code quality: Add asserts to an @After function to test whether the values are in a
	 * reasonable range. E.g. check whether thread count is 30% below the limit, backoff percentage
	 * is below 30%, and ping time is below the default soft ping time limit of fred
	 * {@link NodeStats#DEFAULT_SUB_MAX_PING_TIME}. */
	public final void printNodeStatistics() {
		System.out.println(""); // For readability when being called repeatedly.

		System.out.println("AbstractMultiNodeTest: Running Node threads: " + getRunningThreadCount()
			+ "; limit: " + mThreadLimit);
		
		System.out.println("AbstractMultiNodeTest: Average bulk backoff percentage: "
			+ getAverageBackoffPercentage(false));

		System.out.println("AbstractMultiNodeTest: Average Node ping time: "
			+ getAveragePingTime());
	}

	public final int getRunningThreadCount() {
		// All nodes share the mExecutor so the value of one node should represent all of them.
		return mNodes[0].nodeStats.getActiveThreadCount();
	}

	public final int getAverageBackoffPercentage(boolean realtimeTraffic) {
		float averageBackoffPercentage = 0;
		for(Node n : mNodes) {
			float backoffQuota = (float)n.peers.countBackedOffPeers(realtimeTraffic)
				/ (float)n.peers.countValidPeers();
			averageBackoffPercentage += backoffQuota * 100;
		}
		averageBackoffPercentage /= mNodes.length;
		return round(averageBackoffPercentage);
	}

	/** Returns the average node ping time in milliseconds. */
	public final int getAveragePingTime() {
		double averagePingTime = 0;
		for(Node n : mNodes) {
			averagePingTime += n.nodeStats.getNodeAveragePingTime();
		}
		averagePingTime /= mNodes.length;
		// Even though getNodeAveragePingTime() returns a double it is a millisecond value in the
		// hundreds usually so we can cut off the decimals by rounding it to a long and even cast
		// it to an integer.
		return (int)round(averagePingTime); 
	}

    /**
     * Connect every node to every other node by darknet.
     * TODO: Performance: The topology of this may suck. If it doesn't work then see what fred's
     * {@link RealNodeTest} does. */
    private final void connectNodes()
            throws FSParseException, PeerParseException, ReferenceSignatureVerificationException,
                   PeerTooOldException, InterruptedException, NodeInitException {
        
        System.out.println("AbstractMultiNodeTest: Creating darknet connections...");
        StopWatch time = new StopWatch();
        makeKleinbergNetwork(mNodes);
        System.out.println("AbstractMultiNodeTest: Darknet connections created! Time: " + time);
        
        System.out.println("AbstractMultiNodeTest: Starting nodes...");
        time = new StopWatch();
        for(Node n : mNodes)
            n.start(!ENABLE_SWAPPING);
        System.out.println("AbstractMultiNodeTest: Nodes started! Time: " + time);
        
        System.out.println("AbstractMultiNodeTest: Waiting for nodes to connect...");
        time = new StopWatch();
        boolean connected;
        do {
            connected = true;
            for(Node n : mNodes) {
                if(n.peers.countConnectedDarknetPeers() < n.peers.countValidPeers()) {
                    connected = false;
                    break;
                }
            }
            
            if(!connected)
                sleep(100);
        } while(!connected);
        System.out.println("AbstractMultiNodeTest: Nodes connected! Time: " + time);
        
        // Don't wait for the network to settle, e.g. backoff to reduce:
        // While this does speed up the inserts/requests of e.g. IdentityFetcherTest a bit as of
        // fred build01478 (latest at 2017-09) the speedup isn't enough to compensate for the time
        // it takes for the network to settle (= 2 minutes for 100 nodes on my machine).
        /*
        System.out.println("AbstractMultiNodeTest: Waiting for network to settle...");
        time = new StopWatch();
        while(true) {
            // Don't wait for pings to settle: setUpNode() sets TestNodeParameters.longPingTimes
            if(getAverageBackoffPercentage(false) < 30) {
                // && getAveragePingTime() < NodeStats.DEFAULT_SUB_MAX_PING_TIME) {
                break;
            }
            
            sleep(1000);
        }
        System.out.println("AbstractMultiNodeTest: Network settled! Time: " + time);
        */
    }

	/**
	 * TODO: Code quality: This function is an amended copy-paste of
	 * {@link RealNodeTest#makeKleinbergNetwork(Node[], boolean, int, boolean, RandomSource)} of
	 * fred tag build01478. Make it public there, backport the changes and re-use it instead.
	 * 
	 * Borrowed from mrogers simulation code (February 6, 2008)
	 * 
	 * TODO from fred's source of this function:
	 * May not generate good networks. Presumably this is because the arrays are always scanned
	 * [0..n], some nodes tend to have *much* higher connections than the degree (the first few),
	 * starving the latter ones. */
	private final void makeKleinbergNetwork(Node[] nodes) {
		// These three values are taken from RealNodeRequestInsertTest
		boolean idealLocations = true;
		int degree = 10;
		boolean forceNeighbourConnections = true;
		
		if(idealLocations) {
			// First set the locations up so we don't spend a long time swapping just to stabilise
			// each network.
			double div = 1.0 / nodes.length;
			double loc = 0.0;
			for (int i=0; i<nodes.length; i++) {
				nodes[i].setLocation(loc);
				loc += div;
			}
		}
		if(forceNeighbourConnections) {
			for(int i=0;i<nodes.length;i++) {
				int next = (i+1) % nodes.length;
				connect(nodes[i], nodes[next]);
				
			}
		}
		for (int i=0; i<nodes.length; i++) {
			Node a = nodes[i];
			// Normalise the probabilities
			double norm = 0.0;
			for (int j=0; j<nodes.length; j++) {
				Node b = nodes[j];
				if (a.getLocation() == b.getLocation()) continue;
				norm += 1.0 / distance (a, b);
			}
			// Create degree/2 outgoing connections
			for (int k=0; k<nodes.length; k++) {
				Node b = nodes[k];
				if (a.getLocation() == b.getLocation()) continue;
				double p = 1.0 / distance (a, b) / norm;
				for (int n = 0; n < degree / 2; n++) {
					if (mRandom.nextFloat() < p) {
						connect(a, b);
						break;
					}
				}
			}
		}
	}

	/**
	 * TODO: Code quality: This function is an amended copypaste of
	 * {@link RealNodeTest#connect(Node, Node)}. Make it public there, backport the changes and
	 * re-use it instead. */
	private static final void connect(Node a, Node b) {
		try {
			a.connect(b, FRIEND_TRUST.HIGH, FRIEND_VISIBILITY.YES);
			b.connect(a, FRIEND_TRUST.HIGH, FRIEND_VISIBILITY.YES);
		} catch (FSParseException | PeerParseException | ReferenceSignatureVerificationException
				| PeerTooOldException e) {
			throw new RuntimeException(e);
		}
	}

	/** TODO: Code quality: This function was copy-pasted from {@link RealNodeTest#distance(Node,
	 *  Node)}. Make it public there and re-use it instead. */
	private static final double distance(Node a, Node b) {
		double aL=a.getLocation();
		double bL=b.getLocation();
		return Location.distance(aL, bL);
	}

    /**
     * {@link AbstractJUnit4BaseTest#testDatabaseIntegrityAfterTermination()} is based on this,
     * please apply changes there as well. */
    @After
    @Override
    public final void testDatabaseIntegrityAfterTermination() {
        for(int i = 0; i < getWoTCount(); ++i) {
            Node node = mNodes[i];
            
            // We cannot use Node.exit() because it would terminate the whole JVM.
            // TODO: Code quality: Once fred supports shutting down a Node without killing the JVM,
            // use that instead of only unloading WoT.
            // https://bugs.freenetproject.org/view.php?id=6683
            /* node.exit("JUnit tearDown()"); */
            
            WebOfTrust wot = getWebOfTrust(node);
            File database = wot.getDatabaseFile();
            node.getPluginManager().killPlugin(wot, Long.MAX_VALUE);
            
            // The following commented-out assert would yield a false failure:
            // - setUpNode() already called WebOfTrust.terminateSubsystemThreads().
            // - When killPlugin() calls WebOfTrust.terminate(), that function will try to
            //   terminate() those subsystems again. This will fail because they are terminated
            //   already.
            // - WebOfTrust.terminate() will mark termination as failed due to subsystem termination
            //   failure. Thus, isTerminated() will return false.
            // The compensation for having this assert commented out is the function testTerminate()
            // at AbstractMultiNodeTestSelfTest.
            // TODO: Code quality: It would nevertheless be a good idea to find a way to enable this
            // assert since testTerminate() does not cause load upon the subsystems of WoT and thus
            // is unlikely to trigger bugs. This function here however is an @After test, so it will
            // be run after the child tests classes' tests, which can cause sophisticated load.
            // An alternative solution would be to amend terminateSubystemThreads() and terminate()
            // to be able to track success of shutdown of each individual subsystem.
            // Then terminate() wouldn't have to mark termination as failed when being called with
            // some subsystems having been terminated already by terminateSubystemThreads().
            /* assertTrue(wot.isTerminated()); */
            
            wot = null;
            
            WebOfTrust reopened = new WebOfTrust(database.toString());
            assertTrue(reopened.verifyDatabaseIntegrity());
            assertTrue(reopened.verifyAndCorrectStoredScores());
            reopened.terminate();
            assertTrue(reopened.isTerminated());
        }
    }

    @Override
    protected final WebOfTrust getWebOfTrust() {
        if(mNodes.length > 1)
            throw new UnsupportedOperationException("Running more than one WebOfTrust!");
        
        return getWebOfTrust(mNodes[0]);
    }

    protected static final WebOfTrust getWebOfTrust(Node node) {
        PluginInfoWrapper pluginInfo =
            node.getPluginManager().getPluginInfoByClassName(WebOfTrust.class.getName());
        assertNotNull("Plugin shouldn't be unloaded yet!", pluginInfo);
        return (WebOfTrust)pluginInfo.getPlugin();
    }

    /**
     * {@link AbstractMultiNodeTest} loads WOT as a real plugin just as if it was running in
     * a regular node. This will cause WOT to create the seed identities.<br>
     * If you need to do a test upon a really empty database, use this function to delete them.
     * 
     * @throws UnknownIdentityException
     *             If the seeds did not exist. This is usually an error, don't catch it, let it hit
     *             JUnit.
     * @throws MalformedURLException
     *             Upon internal failure. Don't catch this, let it hit JUnit.
     */
    protected final void deleteSeedIdentities()
            throws UnknownIdentityException, MalformedURLException {
        
        for(int i = 0; i < getWoTCount(); ++i) {
            Node node = mNodes[i];
            WebOfTrust wot = getWebOfTrust(node);
            // Properly ordered combination of locks needed for wot.getAllIdentities(),
            // getAllTrusts(), getAllScores(), beginTrustListImport(), deleteWithoutCommit(Identity)
            // and Persistent.checkedCommit().
            // We need to synchronize because WoT runs its own threads if a child class implements
            // shouldTerminateAllWoTThreads() to return false.
            synchronized(wot) {
            synchronized(wot.getIntroductionPuzzleStore()) {
            synchronized(wot.getIdentityDownloaderController()) {
            synchronized(wot.getSubscriptionManager()) {
            synchronized(Persistent.transactionLock(wot.getDatabase()))  {
            
            assertEquals(WebOfTrust.SEED_IDENTITIES.length, wot.getAllIdentities().size());
            
            // The function for deleting identities deleteWithoutCommit() is mostly a debug function
            // and thus shouldn't be used upon complex databases. See its JavaDoc.
            assertEquals(
                  "This function might have side effects upon databases which contain more than"
                + " just the seed identities, so please do not use it upon such databases.",
                0, wot.getAllTrusts().size() + wot.getAllScores().size());
            
            wot.beginTrustListImport();
            for(String seedURI : WebOfTrust.SEED_IDENTITIES) {
                wot.deleteWithoutCommit(wot.getIdentityByURI(new FreenetURI(seedURI)));
            }
            wot.finishTrustListImport();
            Persistent.checkedCommit(wot.getDatabase(), wot);
            
            assertEquals(0, wot.getAllIdentities().size());
            
            }}}}}
        }
    }
}
