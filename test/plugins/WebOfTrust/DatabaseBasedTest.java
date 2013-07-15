/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import junit.framework.TestCase;
import freenet.crypt.DummyRandomSource;
import freenet.crypt.RandomSource;
import freenet.keys.FreenetURI;
import freenet.keys.InsertableClientSSK;

/**
 * A JUnit <code>TestCase</code> which opens a db4o database in setUp() and closes it in tearDown().
 * The filename of the database is chosen as the name of the test function currently run by db4o and the
 * file is deleted after the database has been closed. When setting up the test, it is assured that the database
 * file does not exist, the test will fail if it cannot be deleted.
 * 
 * The database can be accessed through the member variable <code>db</code>.
 * 
 * You have to call super.setUp() and super.tearDown() if you override one of those methods.
 * 
 * @author xor (xor@freenetproject.org)
 */
public class DatabaseBasedTest extends TestCase {

	protected WebOfTrust mWoT;
	
	protected RandomSource mRandom;

	/**
	 * @return Returns the filename of the database. This is the name of the current test function plus ".db4o".
	 */
	public String getDatabaseFilename() {
		return getName() + ".db4o";
	}

	/**
	 * You have to call super.setUp() if you override this method.
	 */
	@Override
	protected void setUp() throws Exception {
		super.setUp();
		
		File databaseFile = new File(getDatabaseFilename());
		if(databaseFile.exists())
			databaseFile.delete();
		assertFalse(databaseFile.exists());;
		
		mWoT = new WebOfTrust(getDatabaseFilename());
		
		Random random = new Random();
		long seed = random.nextLong();
		mRandom = new DummyRandomSource(seed);
		System.out.println(this + " Random seed: " + seed);
	}

	/**
	 * You have to call super.tearDown() if you override this method. 
	 */
	@Override
	protected void tearDown() throws Exception {
		super.tearDown();
		
		mWoT.terminate();
		
		new File(getDatabaseFilename()).delete();
	}
	
	/**
	 * Generates a String containing random characters of the lowercase Latin alphabet.
	 * @param The length of the returned string.
	 */
	protected String getRandomLatinString(int length) {
		char[] s = new char[length];
		for(int i=0; i<length; ++i)
			s[i] = (char)('a' + mRandom.nextInt(26));
		return new String(s);
	}
	
	/**
	 * Generates a random SSK request-/insert-keypair, suitable for being used when creating identities.
	 * @return An array where slot 0 is the request URI and slot 1 is the insert URI
	 */
	protected FreenetURI[] getRandomSSKPair() {
		InsertableClientSSK ssk = InsertableClientSSK.createRandom(mRandom, "");
		return new FreenetURI[]{ ssk.getInsertURI(), ssk.getURI() };
	}
	
	/**
	 * Generates a random SSK request URI, suitable for being used when creating identities.
	 */
	protected FreenetURI getRandomRequestURI() {
		return InsertableClientSSK.createRandom(mRandom, "").getURI();
	}
	
	/**
	 * Adds identities with random request URIs to the database.
	 * Their state will be as if they have never been fetched: They won't have a nickname, edition will be 0, etc.
	 * 
	 * @param count Amount of identities to add
	 * @return An {@link ArrayList} which contains all added identities.
	 */
	protected ArrayList<Identity> addRandomIdentities(int count) {
		ArrayList<Identity> result = new ArrayList<Identity>(count+1);
		
		while(count-- > 0) {
			try {
				result.add(mWoT.addIdentity(getRandomRequestURI().toString()));
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		}
		
		return result;
	}

	/**
	 * Does nothing. Just here because JUnit will complain if there are no tests.
	 */
	public void testSelf() {
		
	}
	
	protected void flushCaches() {
		System.gc();
		System.runFinalization();
		if(mWoT != null) {
			Persistent.checkedRollback(mWoT.getDatabase(), this, null);
			mWoT.getDatabase().purge();
		}
		System.gc();
		System.runFinalization();
	}

}

