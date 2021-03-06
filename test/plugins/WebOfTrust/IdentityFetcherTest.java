/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import static java.lang.Thread.sleep;
import static org.junit.Assert.*;

import java.net.MalformedURLException;
import java.util.Date;

import org.junit.Before;
import org.junit.Test;

import plugins.WebOfTrust.Identity.FetchState;
import plugins.WebOfTrust.exceptions.InvalidParameterException;
import plugins.WebOfTrust.exceptions.UnknownIdentityException;
import plugins.WebOfTrust.util.StopWatch;
import freenet.node.Node;

/**
 * Tests classes {@link IdentityFetcher} and {@link IdentityInserter} with a real small darknet of
 * ten Freenet {@link Node}s and two {@link WebOfTrust} plugin instances:
 * - Creates an {@link OwnIdentity} on WoT #1 and has the IdentityInserter insert it into Freenet.
 * - Creates another OwnIdentity on WoT #2 which adds the remote Identity by its URI and sets a
 *   positive trust to it.
 * - Waits until the remote Identity is successfully fetched and imported by the IdentityFetcher on
 *   WoT #2 and validates its attributes are equal to the original. */
public final class IdentityFetcherTest extends AbstractMultiNodeTest {

	@Override public int getNodeCount() {
		// As recommended by the specification of this function at AbstractMultiNodeTest.
		return 10;
	}

	@Override public int getWoTCount() {
		return 2;
	}

	@Override public boolean shouldTerminateAllWoTThreads() {
		return false;
	}

	@Override public String getDetailedLogLevel() {
		// Enable DEBUG logging so you can watch progress on stdout while the test is running.
		// Enable logging for the unrelated IdentityFileProcessor as well as it may introduce a
		// processing delay currently which you can observe at its logging (see
		// https://bugs.freenetproject.org/view.php?id=6958).
		return "freenet:NONE,"
		     + "plugins.WebOfTrust.IdentityInserter:DEBUG,"
		     + "plugins.WebOfTrust.IdentityFetcher:DEBUG,"
		     + "plugins.WebOfTrust.IdentityFileProcessor:MINOR";
	}

	@Before public void setUp() throws MalformedURLException, UnknownIdentityException {
		deleteSeedIdentities();
	}

	@Test public void testInsertAndFetch()
			throws MalformedURLException, InvalidParameterException, NumberFormatException,
			UnknownIdentityException, InterruptedException {
		
		Node[] nodes = getNodes();
		WebOfTrust insertingWoT = getWebOfTrust(nodes[0]);
		WebOfTrust fetchingWoT  = getWebOfTrust(nodes[1]);
		
		OwnIdentity insertedIdentity;
		OwnIdentity trustingIdentity;
		
		// Synchronized to prevent the WoTs from doing stuff while we set up the test environment.
		// synchronized & clone() also as workaround for
		// https://bugs.freenetproject.org/view.php?id=6247
		// Notice: As a consequence of the clone() we will have to re-query the identities from the
		// database before we pass them to other functions which use them for database queries,
		// otherwise db4o will not know the objects' references.
		synchronized(insertingWoT) {
		synchronized(fetchingWoT) {
			insertedIdentity = insertingWoT.createOwnIdentity("i1", true, null).clone();
			trustingIdentity = fetchingWoT.createOwnIdentity("i2", true, null).clone();
			
			fetchingWoT.addIdentity(insertedIdentity.getRequestURI().toString());
			// The Identity has to receive a Trust, otherwise it won't be eligible for download.
			fetchingWoT.setTrust(trustingIdentity.getID(), insertedIdentity.getID(), (byte)100, "");
			
			// Disable upload of puzzles to reduce load and thus speed things up.
			// TODO: Code quality: Benchmark whether this actually helps enough to justify having
			// unrelated code here.
			insertingWoT.setPublishIntroductionPuzzles(insertedIdentity.getID(), false);
			fetchingWoT.setPublishIntroductionPuzzles(trustingIdentity.getID(), false);
			
			// This will be equals after the identity was inserted & fetched.
			// (Checking equals() of the whole Identity wouldn't make sense here because comparing
			// an OwnIdentity against a non-own Identity will always return false, so only check
			// the nickname!)
			assertNotEquals(
				insertingWoT.getIdentityByID(insertedIdentity.getID()).getNickname(),
				 fetchingWoT.getIdentityByID(insertedIdentity.getID()).getNickname());
		}}
		
		// Automatically scheduled for execution on a Thread by createOwnIdentity().
		/* insertingWoT.getIdentityInserter().iterate(); */
		
		// Automatically scheduled for execution by setTrust()'s callees.
		/* fetchingWoT.getIdentityFetcher().run(); */
		
		System.out.println("IdentityFetcherTest: Waiting for Identity to be inserted/fetched...");
		StopWatch insertTime = new StopWatch();
		StopWatch fetchTime = new StopWatch();
		boolean inserted = false;
		boolean fetched = false;
		do {
			// Check whether Identity was inserted and print the time it took to insert it.
			// Notice: We intentionally don't wait for this in a separate loop before waiting for it
			// to be fetched: Due to redundancy the amount of data to insert is larger than what
			// has to be fetched, so fred's "insert finished!" callbacks can happen AFTER the remote
			// node's "fetch finished!" callbacks have already happend.
			if(!inserted) {
				synchronized(insertingWoT) {
					OwnIdentity i = insertingWoT.getOwnIdentityByID(insertedIdentity.getID());
					
					if(i.getLastInsertDate().after(new Date(0))) {
						inserted = true;
						System.out.println(
							"IdentityFetcherTest: Identity inserted! Time: " + insertTime);
					}
				}
			}
			
			synchronized(fetchingWoT) {
				Identity remoteView = fetchingWoT.getIdentityByID(insertedIdentity.getID());
				
				if(remoteView.getCurrentEditionFetchState() == FetchState.Fetched)
					fetched = true;
			} // Must not keep the lock while sleeping to allow WoT's threads to acquire it!
			
			if(!fetched)
				sleep(1000);
		} while(!fetched);
		System.out.println("IdentityFetcherTest: Identity fetched! Time: " + fetchTime);
		printNodeStatistics();
		
		// For Identity.equals() to succeed the Identity we use in the comparison must not be an
		// OwnIdentity. deleteOwnIdentity() will replace the OwnIdentity with a non-own one.
		// (We have to do this before terminating subsystems - otherwise an assert at the beginning
		// of deleteOwnIdentity() will fail because it detects that the IdentityFetcher wasn't
		// trying to download the OwnIdentity anymore like it should have been.)
		insertingWoT.deleteOwnIdentity(insertedIdentity.getID());
		
		// Prevent further modifications while we check results.
		insertingWoT.terminateSubsystemThreads();
		fetchingWoT.terminateSubsystemThreads();
		
		Identity originalIdentity   = insertingWoT.getIdentityByID(insertedIdentity.getID());
		Identity downloadedIdentity =  fetchingWoT.getIdentityByID(insertedIdentity.getID());
		
		// Our previous equals()-check of the nickname should now succeed...
		assertEquals(originalIdentity.getNickname(), downloadedIdentity.getNickname());
		// ... and also even equals()-checking the whole identity!
		assertEquals(originalIdentity, downloadedIdentity);
	}

}
