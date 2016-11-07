/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.network.input;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static plugins.WebOfTrust.util.AssertUtil.assertDidNotThrow;
import static plugins.WebOfTrust.util.DateUtil.roundToNearestDay;
import static plugins.WebOfTrust.util.DateUtil.toStringYYYYMMDD;

import java.io.Serializable;
import java.util.Date;

import plugins.WebOfTrust.Identity;
import plugins.WebOfTrust.Identity.IdentityID;
import plugins.WebOfTrust.IdentityFetcher;
import plugins.WebOfTrust.OwnIdentity;
import plugins.WebOfTrust.Persistent;
import plugins.WebOfTrust.Score;
import plugins.WebOfTrust.Trust;
import plugins.WebOfTrust.Trust.TrustID;
import plugins.WebOfTrust.WebOfTrust;
import plugins.WebOfTrust.exceptions.DuplicateObjectException;
import plugins.WebOfTrust.exceptions.UnknownEditionHintException;
import freenet.keys.FreenetURI;
import freenet.keys.USK;
import freenet.support.Base64;
import freenet.support.CurrentTimeUTC;
import freenet.support.IllegalBase64Exception;

/**
 * An EditionHint advertises the latest {@link FreenetURI#getEdition() USK edition} an
 * {@link Identity} has discovered of another Identity.
 * 
 * An {@link USK} {@link FreenetURI} is an updateable key where each update is published at an
 * incremented long integer called the "edition" of the USK.
 * Determining the latest edition N can result in O(N) network queries on the Freenet network.
 * As all M WoT {@link Identity}s are published at USKs, we want to avoid creating the high
 * O(N * M) load required to determine the editions of all their USKs.
 * To do so, Identitys publish EditionHints for other identities. They're transported as "bonus"
 * payload when an Identity uploads its {@link Trust} ratings of other identities.
 * So when we download an edition of an Identity, we automatically get an EditionHint for all the
 * other Identitys it trusts.
 * 
 * (Also, keeping an USK up to date requires a constant polling load on the network, so with M
 * identities, there would be O(M) polls on the network repeating at some time interval forever.)
 * 
 * @see IdentityDownloader#storeNewEditionHintCommandWithoutCommit(EditionHint) */
public final class EditionHint extends Persistent implements Comparable<EditionHint> {

	/** @see Serializable */
	private static final long serialVersionUID = 1L;

	/**
	 * The legacy implementation class {@link IdentityFetcher} will use hints from {@link Identity}s
	 * if merely their {@link Score#getScore()} is > 0, ignoring the {@link Score#getCapacity()}.
	 * The new implementation of {@link IdentityDownloaderSlow} requires their capacity to be > 0.
	 * To allow this class to refuse hints of Identitys with too low capacities when not running
	 * the legacy implementation we need to check whether we're running the legacy implementation.
	 * 
	 * (We do not check whether the Score is > 0 for the legacy implementation, it shall do that
	 * on its own. Our focus is mostly ensuring strict behavior for the new implementation, we don't
	 * care much about the legacy one.) */
	private static final int MIN_CAPACITY
		= IdentityDownloaderController.USE_LEGACY_REFERENCE_IMPLEMENTATION ? 0 : 1;

	private final String mSourceIdentityID;

	@IndexedField
	private final String mTargetIdentityID;
	
	/** @see #getID() */
	@IndexedField
	private final String mID;

	/**
	 * The estimate Date of when the {@link Identity} which gave us the edition hint discovered
	 * the edition. This shall be the first thing we sort upon when trying to decide which hints to
	 * attempt to download first. For hints with equal dates, sorting shall fall back to
	 * {@link #mSourceCapacity}.
	 * 
	 * The closer to current time, the higher the probability that the hint is still correct, so
	 * sorting upon this first should ensure we attempt to download the best hints first. 
	 * 
	 * Since the precision of Dates being milliseconds is rather high, and thus the probability of
	 * two Date being equal is very low, this is rounded to the nearest day to:
	 * - ensure that the fallback sorting actually has a chance to happen. 
	 * - by the above also avoid malicious identities from inserting very many hints to always win
	 *   in the sorting.
	 * 
	 * @see #compareTo_ReferenceImplementation(EditionHint) */
	private final Date mDate;

	/**
	 * {@link WebOfTrust#getBestCapacity(Identity)} of the {@link Identity} which gave us the hint.
	 * For hints with equal {@link #mDate}, this shall be the fallback sorting key to determine
	 * which ones to download first: Hints with higher capacity must be downloaded first.
	 * 
	 * The Identitys which have received a direct {@link Trust} of an {@link OwnIdentity} are
	 * constantly being polled for new editions by the {@link IdentityDownloaderFast}, so their
	 * hints are most up to date and should be downloaded first. Sorting by capacity ensures this as
	 * they will have the highest capacity: Capacity is a direct function of {@link Score#getRank()}
	 * (see {@link WebOfTrust#capacities}) - it higher for lower {@link Score#getRank()}, so
	 * the higher it is, the closer an identity is to the users {@link OwnIdentity}s in the
	 * {@link Trust} graph. For identities directly being trusted by an OwnIdentity, the capacity
	 * will be the highest.
	 * Also as sorting by capacity means sorting by rank, we sort the download order like the
	 * shells of an onion where each shell is a rank step, or in other words: We represent the
	 * topology of the social graph through which the edition hints flow. This hopefully might
	 * ensure fast propagation of hints across the social graph.
	 * 
	 * Also notice:
	 * Normally, we would naively say the next sorting key after {@link #mDate} should be
	 * {@link #mSourceScore} as the Identitys with a high Score are the most trustworthy overall,
	 * but there is a pitfall: If the user assigns a {@link Trust} with an {@link OwnIdentity} to an
	 * Identity, its Score will be always have the same value as the  Trust, so it will always be in
	 * range [-100, 100].
	 * Remote identities can get much higher Scores than that because their Score is the sum of
	 * their Trusts weighted by the trusters' capacities. So with those Scores above 100 they
	 * could always win against OwnIdentiys. But the hints of trustees of OwnIdentitys are much more 
	 * valuable due to the USK subscriptions of {@link IdentityDownloaderFast} - which is why
	 * we sort by capacity first as aforementioned.
	 * 
	 * Furthermore, sorting by capacity is critical to ensure the most "stable" behavior of WoT
	 * - where "stable" means that the results of {@link Score} computation should not depend on
	 * order of download:
	 * The higher the capacity of an Identity, the more voting power it has in {@link Score}
	 * computation. As identities with higher capacity can give higher capacity = voting power to
	 * their trustees, by preferring to download hints with higher capacity, we prefer to download
	 * the identities with the highest potential voting power first.
	 * 
	 * The next fallback sorting key after this one is {@link #mSourceScore}.
	 * It is also ensured that fallback will actually happen:
	 * There are only 7 distinct capacities, see {@link WebOfTrust#capacities}.
	 * 
	 * @see #compareTo_ReferenceImplementation(EditionHint) */
	private final byte mSourceCapacity;

	/**
	 * Rounded {@link WebOfTrust#getBestScore(Identity)} of the {@link Identity} which gave us the
	 * hint. For hints with equal {@link #mSourceCapacity}, this shall be the fallback sorting key
	 * to determine which ones to download first: Hints with higher Score must be downloaded first.
	 * 
	 * As hints are accepted from any {@link Identity} with a {@link Score#getCapacity()} > 0,
	 * hints will also be accepted from Identitys with a negative Score. As a negative Score
	 * generally means "this identity is rated as a spammer by the community", we must be very
	 * careful with the hints we received from them. So by having some level of sorting based on the
	 * Score, we ensure that non-spammers are preferred.
	 * 
	 * As there are many distinct Score values, the Score is rounded to one of the two values
	 * {-1, 1} to merely represent whether the Score was < 0 or >= 0, i.e. whether the source is
	 * a distrusted or trusted Identity. Reducing the amount of distinct values is needed to ensure
	 * we have a chance to actually fallback to {@link #mEdition} as sorting key:
	 * We need to make sure that we try downloading higher editions first as a low edition is
	 * worthless if a higher one exists.
	 * 
	 * @see #compareTo_ReferenceImplementation(EditionHint) */
	private final byte mSourceScore;

	/**
	 * The actual edition hint itself.
	 * This is the final fallback sorting key after we sorted hints by {@link #mDate},
	 * {@link #mSourceCapacity} and {@link #mSourceScore} and all of them were equal.
	 * 
	 * Notice: We only sort by edition if {@link #mTargetIdentityID} is equal for the two
	 * EditionHint objects at comparison. Editions of different identities are completely unrelated,
	 * it wouldn't make any sense to compare them.
	 *
	 * @see #compareTo_ReferenceImplementation(EditionHint) */
	private final long mEdition;

	/**
	 * A concatenation of the multiple sorting keys used by
	 * {@link #compareTo_ReferenceImplementation(EditionHint)} which shall result in the same
	 * sorting order when using {@link String#compareTo(String)} to sort upon this single field
	 * instead.
	 * 
	 * This is for allowing fast database queries using db4o:
	 * db4o only supports one-dimensional indexes, i.e. indexes upon a single field so we must
	 * combine the multiple sorting keys into one single variable.
	 * 
	 * (The most efficient storage for this would be byte[], but IIRC db4o does not handle that as
	 * well as String.)
	 * 
	 * @see #compareTo_ReferenceImplementation(EditionHint)
	 * @see #compareTo(EditionHint) */
	@IndexedField
	private final String mPriority;


	/** Factory with parameter validation */
	public static EditionHint constructSecure(
			WebOfTrust wot, String sourceIdentityID, String targetIdentityID, Date date,
			int sourceCapacity, int sourceScore, long edition) {
		
		requireNonNull(wot);
		
		IdentityID.constructAndValidateFromString(sourceIdentityID);
		IdentityID.constructAndValidateFromString(targetIdentityID);
		if(sourceIdentityID.equals(targetIdentityID)) {
			throw new IllegalArgumentException(
				"Identity is trying to assign edition hint to itself, ID: " + sourceIdentityID);
		}
		
		if(date.after(CurrentTimeUTC.get()))
			throw new IllegalArgumentException("Invalid date: " + date);
		
		// Hints should only be accepted if the capacity is >= MIN_CAPACITY.
		// Also, the capacity should be in the valid range of WebOfTrust.capacities.
		// TODO: Code quality: Use WebOfTrust.VALID_CAPCITIES once branch
		// issue-0006895-full-tests-for-Score is merged
		if(sourceCapacity < MIN_CAPACITY || sourceCapacity > 100)
			throw new IllegalArgumentException("Invalid capacity: " + sourceCapacity);
		
		// Cannot validate the score: All values of int are acceptable Score values.

		if(edition < 0)
			throw new IllegalArgumentException("Invalid edition: " + edition);
		
		return new EditionHint(
			wot, sourceIdentityID, targetIdentityID, date, sourceCapacity, sourceScore, edition);
	}

	/** Factory WITHOUT parameter validation */
	static EditionHint construcInsecure(
			final WebOfTrust wot, final String sourceIdentityID, final String targetIdentityID,
			final Date date, final int sourceCapacity, final int sourceScore, final long edition) {
		
		assertDidNotThrow(new Runnable() { @Override public void run() {
			constructSecure(wot, sourceIdentityID, targetIdentityID, date, sourceCapacity,
				sourceScore, edition);
		}});
		
		return new EditionHint(
			wot, sourceIdentityID, targetIdentityID, date, sourceCapacity, sourceScore, edition);
	}

	private EditionHint(
			WebOfTrust wot, String sourceIdentityID, String targetIdentityID, Date date,
			int sourceCapacity, int sourceScore, long edition) {
		
		initializeTransient(wot);
		mSourceIdentityID = sourceIdentityID;
		mTargetIdentityID = targetIdentityID;
		mDate = roundToNearestDay(date);
		mSourceCapacity = (byte)sourceCapacity;
		mSourceScore = sourceScore >= 0 ? (byte)1 : (byte)-1;
		mEdition = edition;
		
		mPriority = computePriority(
			wot, mDate, mSourceCapacity, mSourceScore, mTargetIdentityID, mEdition);
		mID = new TrustID(mSourceIdentityID, mTargetIdentityID).toString();
	}

	public String getSourceIdentityID() {
		// String is a db4o primitive type so 1 is enough even though it is a reference type
		checkedActivate(1);
		return mSourceIdentityID;
	}

	public String getTargetIdentityID() {
		// String is a db4o primitive type so 1 is enough even though it is a reference type
		checkedActivate(1);
		return mTargetIdentityID;
	}

	public byte getSourceCapacity() {
		checkedActivate(1);
		return mSourceCapacity;
	}

	public long getEdition() {
		checkedActivate(1);
		return mEdition;
	}

	private static String computePriority(WebOfTrust wot, Date roundedDate, byte capacity,
			int roundedScore, String targetID, long edition) {
		
		assert(roundedDate.equals(roundToNearestDay(roundedDate)));
		assert(capacity >= MIN_CAPACITY && capacity <= 100);
		assert(roundedScore == -1 || roundedScore == 1);
		assert(edition >= 0);
		
		// We want to include the targetID in the result String to be compliant with
		// compareTo_ReferenceImplementation()'s desire of not falling back to the edition as
		// sorting key for Identities with different IDs.
		// Unfortunately, this also has the side-effect that IDs such as "aaaaaa..." have a higher
		// priority than "b...".  As the targetID is the Identity.getID() of a *remote* Identity,
		// and is the hash of their self-generated public key, this would allow attackers to
		// bruteforce public-key generation to get an ID of "aaaa..." in order to maliciously get
		// a higher priority.
		// To prevent that, we encrypt the ID with a local, non-public random pad.
		targetID = encryptIdentityID(wot, targetID);
		
		int length = 8 + 3 + 1 + IdentityID.LENGTH + 19;
		
		StringBuilder sb = new StringBuilder(length);
		sb.append(toStringYYYYMMDD(roundedDate));
		sb.append(format("%03d", capacity));
		sb.append(format("%d", roundedScore == -1 ? 0 : 1));
		sb.append(targetID);
		sb.append(format("%019d", edition));
		
		assert(sb.capacity() == length);
		
		return sb.toString();
	}

	static String encryptIdentityID(WebOfTrust keyProvider, String id) {
		byte[] idBytes;
		try {
			// TODO: Code quality: Move to class IdentityID
			idBytes = Base64.decode(id);
		} catch (IllegalBase64Exception e) {
			throw new RuntimeException(e);
		}
		
		byte[] pad = keyProvider.getConfig().getConstantRandomPad();
		if(idBytes.length > pad.length)
			throw new IllegalArgumentException("getConstantRandomPad() too small: " + pad.length);
		
		for(int i = 0; i < idBytes.length; ++i)
			idBytes[i] ^= pad[i];
		
		// TODO: Code quality: Move to class IdentityID
		return Base64.encode(idBytes);
	}

	static String decryptIdentityID(WebOfTrust keyProvider, String id) {
		return encryptIdentityID(keyProvider, id);
	}

	private String getPriority() {
		// String is a db4o primitive type so 1 is enough even though it is a reference type
		checkedActivate(1);
		return mPriority;
	}

	/** Returns the sort ordering which the {@link IdentityDownloader} should use. */
	@Override public int compareTo(EditionHint o) {
		checkedActivate(1);
		return getPriority().compareTo(o.getPriority());
	}

	/**
	 * For testing purposes:
	 * Same as {@link #compareTo(EditionHint)} but does not the {@link #mPriority} sorting key.
	 * It instead compares the member variables from which the sorting key should be build using
	 * {@link #computePriority(WebOfTrust, Date, int, int, String, long)}.
	 * This can be used to test the validity of the sorting key. */
	int compareTo_ReferenceImplementation(EditionHint o) {
		this.activateFully();
		o.activateFully();
		
		int dateCompared = mDate.compareTo(o.mDate);
		if(dateCompared != 0)
			return dateCompared;
		
		int capacityCompared = Byte.compare(mSourceCapacity, o.mSourceCapacity);
		if(capacityCompared != 0)
			return capacityCompared;
		
		int scoreCompared = Byte.compare(mSourceScore, o.mSourceScore);
		if(scoreCompared != 0)
			return scoreCompared;

		// The commented-out code was deferred to allow the fast non-reference compareTo() to work
		// - which is based upon String sorting on the string returned by computePriority() and
		// stored in mPriority.
		// The commented-out code is what we would do here if the mPriority member variable wasn't
		// supposed to be used for fast sorting instead of using this function.
		// We instead do what comes after the commented-out code as a "mathematical" trick to have
		// the same resulting sort order both when using this reference implementation AND when
		// sorting on mPriority.
		// After you've read the optimized computePriority() to see how mPriority is initialized
		// you may understand the difference between the commented-out code and what we actually do:
		// The commented-out code would always return 0 if the Identity IDs are non-equal because it
		// makes no sense to compare edition numbers for different identities AND we don't care
		// about the IDs of the identities as a sorting key, they're just random letters.
		// The actual non-commented-out code WILL return the result of the comparison of the
		// IDs even though they are just random stuff:
		// This is necessary because sorting on mPriority will also have to do that:
		// mPriority is just a flat string. If we want to prevent sorting on mEdition for different
		// identities, we must somehow trick the String sorting functions to not compare the edition
		// contained in the String if the two compared Strings involve different identities. This is
		// established by putting the identity ID into the mPriority String *before* the edition.
		// So if the IDs are non-equal, the String comparison function will return before reaching
		// the edition. AND: Because String's compareTo() is inaware of the meaning of our strings,
		// it will return the result of comparing the ID substrings
		// - so overall we must do that here in the non-optimized (= non-String based) sorting
		// function as well.
		// Notice: We don't use the actual ID but encrypt it with a persistent, random key to
		// to prevent attackers from maliciously brute forcing pubkey generation to get a hash with
		// prefix "aaaa..." to boost priority of their identity.
		
		/*
		if(mTargetIdentityID.equals(o.mTargetIdentityID))
			return Long.compare(mEdition, o.mEdition);
		else
			return 0; // No sense in comparing edition of different target identities
		*/
		
		WebOfTrust keyProvider = (WebOfTrust)mWebOfTrust;
		String encryptedID1 = encryptIdentityID(keyProvider,   mTargetIdentityID);
		String encryptedID2 = encryptIdentityID(keyProvider, o.mTargetIdentityID);
		
		int targetIDCompared = encryptedID1.compareTo(encryptedID2);
		if(targetIDCompared != 0)
			return targetIDCompared;
		
		return Long.compare(mEdition, o.mEdition);
	}

	/** Equals: <code>new TrustID(getSourceIdentityID(), getTargetIdentityID()).toString()</code> */
	@Override public String getID() {
		// String is a db4o primitive type so 1 is enough even though it is a reference type
		checkedActivate(1);
		return mID;
	}

	@Override public void startupDatabaseIntegrityTest() throws Exception {
		activateFully();
		
		// Will throw if any of the passed member variables is invalid.
		// mID and mPriority are computed from them, we will check them against the returned object.
		// (The cast from webOfTrustInterface to WebOfTrust is valid because the whole existence
		// of this function is a detail of the implementation, so we may cast the WoT to it.)
		EditionHint this2 = constructSecure((WebOfTrust)mWebOfTrust, mSourceIdentityID,
			mTargetIdentityID, mDate, mSourceCapacity, mSourceScore, mEdition);
		
		if(!mID.equals(this2.mID))
			throw new IllegalStateException("mID is invalid: " + this);
		
		// Check for whether there is only one hint with our mID
		try {
			EditionHint queried
				= new IdentityDownloaderSlow((WebOfTrust)mWebOfTrust).getEditionHintByID(mID);
			
			if(queried != this)
				throw new RuntimeException("getEditionHintByID() returned wrong hint for: " + mID);
		} catch(DuplicateObjectException | UnknownEditionHintException e) {
			throw new IllegalStateException(e);
		}
		
		if(!mPriority.equals(this2.mPriority))
			throw new IllegalStateException("mPriority is invalid: " + this);
		
		// While we now know that the member variables are valid from calling constructSecure(),
		// calling that cannot check whether they've also been rounded as they should as it does
		// expect unrounded input. So we need to check the rounding:
		
		if(!mDate.equals(roundToNearestDay(mDate)))
			throw new IllegalStateException("mDate is not rounded: " + this);
		
		// No need to check capacity for proper rounding: It is not supposed to be rounded as there
		// are only 6 different ones anyway (technically 7 but we don't accept capacity == 0 here).
		// See WebOfTrust.capacities.
		
		if(mSourceScore != -1 && mSourceScore != 1)
			throw new IllegalStateException("mSourceScore is not rounded: " + this);
		
		// mWebOfTrust is of type WebOfTrustInterface which doesn't contain special functions of
		// the specific implementation which we need for testing our stuff - so we cast to the
		// implementation.
		// This is ok to do here: This function being a debug one for the specific implementation
		// is fine to depend on technical details of it.
		WebOfTrust wot = ((WebOfTrust)mWebOfTrust);
		
		Identity source = wot.getIdentityByID(mSourceIdentityID);
		Identity target = wot.getIdentityByID(mTargetIdentityID);
		
		// Don't check whether the capacity is still the same as we stored:
		// For performance the IdentityDownloaderSlow implementation will likely not update all
		// hints on granular capacity changes, it will only update them if the capacity changes
		// significantly, i.e. from > 0 to == 0.
		// (Notice: If IdentityDownloaderController.USE_LEGACY_REFERENCE_IMPLEMENTATION is true,
		// MIN_CAPACITY is 0 and theoretically the below would be legal then. But the legacy
		// implementation does NOT store EditionHint objects to the database, so this whole
		// function shouldn't be called with it.)
		if(wot.getBestCapacity(source) == 0) {
			throw new IllegalStateException(
				"Identity which isn't allowed to store hints has stored one: " + this);
		}
		
		if(mEdition <= target.getEdition())
			throw new IllegalStateException("Hint is obsolete: " + this);
		
		// The legacy hinting implementation Identity.getLatestEditionHint() stores only the highest
		// edition hint we received from any identity with a Score of > 0.
		if(mEdition > target.getLatestEditionHint() && wot.getBestScore(source) > 0)
			throw new IllegalStateException("Legacy getLatestEditionHint() too low for: " + target);
	}

	/**
	 * Activates to depth 1 which is the maximal depth of all getter functions.
	 * You must adjust this when introducing new member variables! */
	@Override protected void activateFully() {
		checkedActivate(1);
	}

	@Override protected void storeWithoutCommit() {
		activateFully();
		super.storeWithoutCommit();
	}

	@Override protected void deleteWithoutCommit() {
		activateFully();
		super.deleteWithoutCommit();
	}

	@Override public String toString() {
		activateFully();
		return "[EditionHint: " + super.toString()
		     + "; mID: " + mID
		     + "; mSourceIdentityID: " + mSourceIdentityID
		     + "; mTargetIdentityID: " + mTargetIdentityID
		     + "; mDate: " + mDate
		     + "; mSourceCapacity: " + mSourceCapacity
		     + "; mSourceScore: " + mSourceScore
		     + "; mEdition: " + mEdition
		     + "; mPriority: " + mPriority
		     + "]";
	}

}