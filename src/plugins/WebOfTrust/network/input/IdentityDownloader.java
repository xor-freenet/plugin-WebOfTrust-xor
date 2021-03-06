/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.network.input;

import plugins.WebOfTrust.Identity;
import plugins.WebOfTrust.IdentityFetcher;
import plugins.WebOfTrust.IdentityFile;
import plugins.WebOfTrust.IdentityFileProcessor;
import plugins.WebOfTrust.IdentityFileQueue;
import plugins.WebOfTrust.OwnIdentity;
import plugins.WebOfTrust.Persistent.NeedsTransaction;
import plugins.WebOfTrust.Score;
import plugins.WebOfTrust.SubscriptionManager;
import plugins.WebOfTrust.Trust;
import plugins.WebOfTrust.WebOfTrust;
import plugins.WebOfTrust.XMLTransformer;
import plugins.WebOfTrust.network.input.IdentityDownloaderFast.DownloadSchedulerCommand;
import plugins.WebOfTrust.util.Daemon;
import freenet.keys.FreenetURI;

/**
 * Downloads {@link Identity} objects from the P2P network.
 * Strictly all of them are then fed as {@link IdentityFile} to the {@link IdentityFileQueue}, which
 * the {@link IdentityFileProcessor} polls and feeds into the {@link XMLTransformer}.
 * 
 * Implementations must tolerate loss of the output {@link IdentityFileQueue} across restarts of
 * WoT as required by the JavaDoc of that interface!  
 * For how to ensure this see {@link #onNewEditionImported(Identity)}.
 * 
 * Implementations are allowed to and do store pointers to {@link Identity} and {@link OwnIdentity}
 * objects in their database, e.g. as part of {@link EditionHint} objects and
 * {@link DownloadSchedulerCommand}s.
 * They must not store references to any other objects which are not a type managed by their own
 * database, e.g. {@link Trust} or {@link Score} (because this interface only has callbacks for
 * changes to Identity objects).
 * 
 * Synchronization:
 * 
 * Most (but not all!) callbacks are guaranteed to be called while the following locks are being
 * held in the following order for the following purposes:
 * 1. synchronized(Instance of WebOfTrust):
 *      In order to allow them to access the db4o database table of the Identity, Trust and Score
 *      objects. Especially the Score objects, and in some cases the Trust objects, will be
 *      necessary for them to decide whether to download an Identity.
 * 2. synchronized({@link WebOfTrust#getIdentityDownloaderController()}):
 *      To serve as central db4o database "table" lock for all objects which IdentityDownloader
 *      implementations store inside the db4o database. These are e.g {@link EditionHint} and
 *      {@link DownloadSchedulerCommand} objects.
 *      Having the central lock for the implementations be dictated to be the single
 *      {@link IdentityDownloaderController} allows the code which calls the callbacks to provide
 *      thread-safety independent of how multiple IdentityDownloader implementations, which can be
 *      running in parallel, work internally.
 * 3. synchronized(Persistent.transactionLock(WebOfTrust.getDatabase())):
 *      Due to the callbacks being part of a database transaction and hence being allowed to change
 *      the database contents.
 * 
 * Thus any threads which implementations run on their own must use at least locks 2 and 3 to guard
 * access to their own objects in their db4o database. This ensures concurrent calls to the
 * callbacks by WoT threads are thread-safe.
 * If an implementation also stores references to Identity objects inside of its own objects, as
 * allowed above, then it must further take lock 1 before taking the other ones.
 * 
 * TODO: Code quality: Rename the event handlers to "on...()".
 *
 * TODO: Code quality: Sort callbacks in an order which makes their purpose the most easy to
 * understand, also in implementing classes.
 * 
 * TODO: Code quality: Review the whole of class {@link IdentityFetcher}, which is the reference
 * implementation of Identity downloading we had before this one, for any important JavaDoc and add
 * it to this interface.
 * 
 * FIXME: Once all FIXMEs here are finished review call hierarchy of all callbacks once more for
 * whether they're being called in the correct places. */
public interface IdentityDownloader extends Daemon {

	/**
	 * Is called by {@link WebOfTrust#runPlugin(freenet.pluginmanager.PluginRespirator)} at
	 * startup of the plugin / Freenet.
	 * Should schedule starting the downloads of all Identitys which are eligible for download.
	 * This is necessary because:
	 * - IdentityDownloader implementations are not supposed to create persistent downloads at
	 *   the Freenet node, downloads will typically be lost during restart of Freenet.
	 * - WoT will **NOT** initialize the downloader by calling download scheduling callbacks such as
	 *   {@link #storeStartFetchCommandWithoutCommit(Identity)} for eligible Identitys at startup.
	 *   The download scheduling callbacks are only deployed for changes during the runtime!
	 * 
	 * Must be safe against concurrent calls to {@link #terminate()}.
	 * 
	 * Concurrent calls to the download scheduling callbacks are not to be expected as of the
	 * current implementation of runPlugin().
	 * Nevertheless this function should be implemented to be safe against this changing in the
	 * future. This can typically be guaranteed by the following locking pattern:
	 * <code>
	 * synchronized(Instance of WebOfTrust) { // If Identity objects are queried/used
	 * synchronized(WebOfTrust.getIdentityDownloaderController()) {
	 * synchronized(Persistent.transactionLock(WebOfTrust.getDatabase())) {
	 *     try {
	 *        deleteExistingCommands();
	 *        storeStartFetchCommandsForAllEligibleIdentitiys();
	 *        scheduleCommandProcesing();
	 *        Persistent.checkedCommit(database, this);
	 *     } catch(RuntimeException e) {
	 *        Persistent.checkedRollbackAndThrow(database, this, e);
	 *     }
	 * }}}
	 * </code>*/
	@Override public void start();

	/**
	 * Is called by {@link WebOfTrust#terminate()} at shutdown of WoT.
	 * Should stop all downloads which the IdentityDownloader has created at Freenet.
	 * 
	 * Must be safe against concurrent calls to {@link #start()}.
	 * 
	 * Must be safe against concurrent calls to the download scheduling callbacks (such as e.g.
	 * {@link #storeStartFetchCommandWithoutCommit(Identity)}):
	 * {@link WebOfTrust#terminate()} terminates all WoT subsystems concurrently and thus subsystems
	 * which call the download scheduling callbacks may still operate while the IdentityDownloader's
	 * terminate() is running, or even aftwards.
	 * 
	 * This can typically be guaranteed by the following locking pattern:
	 * <code>
	 * synchronized(WebOfTrust.getIdentityDownloaderController()) {
	 *     disableDownloadCommandProcessing();
	 *     abortExistingDownloadsAtFreenet();
	 * }
	 * </code> */
	@Override public void terminate();

	/**
	 * Called by {@link WebOfTrust#deleteOwnIdentity(String)} before any action is taken towards
	 * deleting an {@link OwnIdentity}.
	 * 
	 * After the callback returns the oldIdentity will be deleted from the database.
	 * It will be replaced by a non-own {@link Identity} object. Its given and received
	 * {@link Trust}s, and its received {@link Score}s will keep existing by being replaced with
	 * objects which to point to the replacement Identity.
	 * Any Scores the oldIdentity has given to other Identitys as specified by
	 * {@link WebOfTrust#getGivenScores(OwnIdentity)} will be deleted then.
	 * 
	 * After this callback has returned, and once the replacement Identity has been created and the
	 * {@link Trust} and Score database fully adapted to it, WoT will call
	 * {@link #storePostDeleteOwnIdentityCommand(Identity)} in order to allow implementations to
	 * start download of the replacement Identity if it is eligible for download.
	 * 
	 * Thus implementations have to:
	 * - remove any object references to the oldIdentity object from their db4o database as they
	 *   would otherwise be nulled by the upcoming deletion of it.
	 * - stop downloading of any Identitys who aren't eligible for download anymore because
	 *   they were eligible solely due to one of the to-be-deleted Scores (see the JavaDoc of
	 *   {@link Score} for when Scores justify downloading an Identity).
	 * - stop downloading the oldIdentity (if it was eligible for download due to having received
	 *   a self-assigned Score, see {@link WebOfTrust#initTrustTreeWithoutCommit(OwnIdentity}).
	 * 
	 * ATTENTION: Identitys which had received a Score from the oldIdentity may still be eligible
	 * for download due to a Score received by a different OwnIdentity! Before aborting their
	 * download check their other received Scores using {@link WebOfTrust#getScores(Identity)} and
	 * {@link WebOfTrust#shouldMaybeFetchIdentity(Score)} for whether any of them justifies to keep
	 * downloading the Identity.
	 * 
	 * Implementations can assume that when this function is called:
	 * - the OwnIdentity still is stored in the database, the replacement Identity object has not
	 *   been created yet.
	 * - the Trust and Score database has not been changed yet.
	 * 
	 * Synchronization:
	 * This function is guaranteed to be called while the following locks are being held in the
	 * given order:
	 * synchronized(Instance of WebOfTrust)
	 * synchronized(WebOfTrust.getIdentityDownloaderController())
	 * synchronized(Persistent.transactionLock(WebOfTrust.getDatabase())) */
	@NeedsTransaction void storePreDeleteOwnIdentityCommand(OwnIdentity oldIdentity);

	/**
	 * Called by {@link WebOfTrust#deleteOwnIdentity(String)} as the very last step of deleting
	 * an {@link OwnIdentity}.
	 * This implies that:
	 * - the OwnIdentity has been deleted from the the database, the given replacement
	 *   {@link Identity} object has been stored.
	 * - the {@link Trust} and {@link Score} database has been fully updated to reflect the
	 *   necessary changes.
	 * 
	 * FIXME: {@link #storePreDeleteOwnIdentityCommand(OwnIdentity)} documents more of the duties
	 * of this callback, should be documented here as well.
	 * 
	 * Synchronization:
	 * This function is guaranteed to be called while the following locks are being held in the
	 * given order:
	 * synchronized(Instance of WebOfTrust)
	 * synchronized(WebOfTrust.getIdentityDownloaderController())
	 * synchronized(Persistent.transactionLock(WebOfTrust.getDatabase())) */
	@NeedsTransaction void storePostDeleteOwnIdentityCommand(Identity newIdentity);

	/**
	 * Called by {@link WebOfTrust#deleteWithoutCommit(Identity)} before any action is taken towards
	 * deleting an {@link Identity}.
	 * 
	 * After the callback returns the oldIdentity will be deleted from the database.
	 * In opposite to {@link WebOfTrust#deleteOwnIdentity(String)} there will be no replacement
	 * Identity object created for the deleted Identity - even if it was an {@link OwnIdentity}!
	 * Any {@link Trust}s and {@link Score}s it has given or received will be deleted, see:
	 * - {@link WebOfTrust#getGivenTrusts(Identity)}
	 * - {@link WebOfTrust#getReceivedTrusts(Identity)}
	 * - {@link WebOfTrust#getGivenScores(OwnIdentity)} if the Identity was an {@link OwnIdentity}.
	 * - {@link WebOfTrust#getScores(Identity)}
	 * 
	 * After this callback has returned, in opposite to the other callbacks of this interface, no
	 * such callback as "storePostDeleteIdentityCommand()" will be called. This is because:
	 * - there will be no replacement Identity to pass by a callback.
	 * - deletion of an Identity can only cause abortion of downloads, not starting - which would
	 *   typically be the job of a Post-deletion version of this callback with starting the download
	 *   of the replacement Identity if necessary, but there will be none.
	 * 
	 * Thus implementations have to:
	 * - remove any object references to the oldIdentity object from their db4o database as they
	 *   would otherwise be nulled by the upcoming deletion of it.
	 * - stop downloading of any Identitys who aren't eligible for download anymore because
	 *   they were eligible solely due to one of the to-be-deleted Scores (see the JavaDoc of
	 *   {@link Score} for when Scores justify downloading an Identity).
	 * - stop downloading the oldIdentity.
	 * 
	 * ATTENTION: Identitys which had received a Score from the oldIdentity may still be eligible
	 * for download due to a Score received by a different OwnIdentity! Before aborting their
	 * download check their other received Scores using {@link WebOfTrust#getScores(Identity)} and
	 * {@link WebOfTrust#shouldMaybeFetchIdentity(Score)} for whether any of them justifies to keep
	 * downloading the Identity.
	 * 
	 * Implementations can assume that when this function is called:
	 * - the Identity still is stored in the database.
	 * - the Trust and Score database has not been changed yet.
	 * 
	 * Synchronization:
	 * This function is guaranteed to be called while the following locks are being held in the
	 * given order:
	 * synchronized(Instance of WebOfTrust)
	 * synchronized(WebOfTrust.getIdentityDownloaderController())
	 * synchronized(Persistent.transactionLock(WebOfTrust.getDatabase())) */
	@NeedsTransaction void storePreDeleteIdentityCommand(Identity oldIdentity);

	// There is no replacement Identity when a non-own Identity is deleted.
	/* @NeedsTransaction void storePostDeleteIdentityCommand(Identity newIdentity); */

	/**
	 * Called by {@link WebOfTrust#restoreOwnIdentity(FreenetURI)} before any action is taken
	 * towards restoring an {@link OwnIdentity}.
	 * 
	 * After the callback returns the non-own oldIdentity will be deleted from the database.
	 * It will be replaced by an {@link OwnIdentity} object. Its given and received
	 * {@link Trust}s, and its received {@link Score}s will keep existing by being replaced with
	 * objects which to point to the replacement OwnIdentity.
	 * (No given Scores could have existed for the oldIdentity because only OwnIdentitys are allowed
	 * to give Scores.)
	 * 
	 * After this callback has returned, and once the replacement OwnIdentity has been created and
	 * the {@link Trust} and Score database fully adapted to it, WoT will call
	 * {@link #storePostRestoreOwnIdentityCommand(OwnIdentity)} in order to allow implementations to
	 * start download of both the replacement OwnIdentity if it is eligible for download as well as
	 * the recipients of its newly created positive {@link WebOfTrust#getGivenScores(OwnIdentity)}.
	 * 
	 * Thus implementations have to:
	 * - remove any object references to the oldIdentity object from their db4o database as they
	 *   would otherwise be nulled by the upcoming deletion of it.
	 * - stop downloading the oldIdentity.
	 * 
	 * Implementations can assume that when this function is called:
	 * - the Identity still is stored in the database, the replacement OwnIdentity object has not
	 *   been created yet.
	 * - the Trust and Score database has not been changed yet.
	 * 
	 * Synchronization:
	 * This function is guaranteed to be called while the following locks are being held in the
	 * given order:
	 * synchronized(Instance of WebOfTrust)
	 * synchronized(WebOfTrust.getIdentityDownloaderController())
	 * synchronized(Persistent.transactionLock(WebOfTrust.getDatabase())) */
	@NeedsTransaction void storePreRestoreOwnIdentityCommand(Identity oldIdentity);

	/**
	 * Called by {@link WebOfTrust#restoreOwnIdentity(FreenetURI)} after an {@link OwnIdentity}
	 * was restored, either by replacing a non-own {@link Identity} with it or by creating it from
	 * scratch.
	 * 
	 * This implies that:
	 * - the non-own Identity has been deleted from the the database, the given replacement
	 *   OwnIdentity object has been stored.
	 * - the {@link Trust} and {@link Score} database has been fully updated to reflect the
	 *   necessary changes.
	 * 
	 * For understanding the surrounding conditions of restoreOwnIdentity() please read the
	 * documentation of {@link #storePreRestoreOwnIdentityCommand(Identity)} which is called before
	 * this callback here.
	 * 
	 * Implementations have to:
	 * - start the download of the given newIdentity.
	 * - If a download is already running adjust the edition to the
	 *   {@link Identity#getNextEditionToFetch()} of the newIdentity:
	 *   The user may have provided a {@link FreenetURI#getSuggestedEdition()} in the
	 *   USK URI when restoring the OwnIdentity.
	 * 
	 * NOTICE: Implementations do NOT have to start the download of the {@link Trust} and
	 * {@link Score} recipients of the OwnIdentity.
	 * This is because for technical reasons the downloads to be started from the given Trusts and
	 * Scores of the newIdentity will have already been started by other callbacks having been
	 * triggered by restoreOwnidentity().
	 * These callbacks might e.g. be:
	 * - {@link #storeStartFetchCommandWithoutCommit(Identity)}
	 * - {@link #storeTrustChangedCommandWithoutCommit(Trust, Trust)}
	 * Implementations of this callback here must be safe against side effects from that.
	 * 
	 * Synchronization:
	 * This function is guaranteed to be called while the following locks are being held in the
	 * given order:
	 * synchronized(Instance of WebOfTrust)
	 * synchronized(WebOfTrust.getIdentityDownloaderController())
	 * synchronized(Persistent.transactionLock(WebOfTrust.getDatabase())) */
	@NeedsTransaction void storePostRestoreOwnIdentityCommand(OwnIdentity newIdentity);

	/**
	 * Called by {@link WebOfTrust}:
	 * - as soon as {@link WebOfTrust#shouldFetchIdentity(Identity)} changes from false to true for
	 *   the given {@link Identity}. This is usually the case when *any* {@link OwnIdentity} has
	 *   rated it as trustworthy enough for us to download it.
	 *   The {@link Trust} and {@link Score} database is guaranteed to be up to date when this
	 *   function is called and thus can be used by it.
	 * - when an OwnIdentity is created (but not when it is deleted/restored, see the other
	 *   callbacks for that).
	 * - May also be called to notify the IdentityDownloader about a changed
	 *   {@link Identity#getNextEditionToFetch()} (e.g. due to  {@link Identity#markForRefetch()})
	 *   even if the Identity was already eligible for fetching before.
	 * 
	 * Synchronization:
	 * This function is guaranteed to be called while the following locks are being held in the
	 * given order:
	 * synchronized(Instance of WebOfTrust)
	 * synchronized(WebOfTrust.getIdentityDownloaderController())
	 * synchronized(Persistent.transactionLock(WebOfTrust.getDatabase())) */
	void storeStartFetchCommandWithoutCommit(Identity identity);

	/**
	 * Called by {@link WebOfTrust}:
	 * - as soon as {@link WebOfTrust#shouldFetchIdentity(Identity)} changes from true to false for
	 *   the given {@link Identity}. This is usually the case when not even one {@link OwnIdentity}
	 *   has rated it as trustworthy enough for us to download it.
	 *   The {@link Trust} and {@link Score} database is guaranteed to be up to date when this
	 *   function is called and thus can be used by it.
	 * - but is not called upon deletion/restoring of an OwnIdentity, see the other callbacks for
	 *   that.
	 * 
	 * Synchronization:
	 * This function is guaranteed to be called while the following locks are being held in the
	 * given order:
	 * synchronized(Instance of WebOfTrust)
	 * synchronized(WebOfTrust.getIdentityDownloaderController())
	 * synchronized(Persistent.transactionLock(WebOfTrust.getDatabase())) */
	void storeAbortFetchCommandWithoutCommit(Identity identity);

	/**
	 * Called under almost the same circumstances as
	 * {@link SubscriptionManager#storeTrustChangedNotificationWithoutCommit()} except for the
	 * following differences:
	 * 
	 * - The {@link Trust} *and* {@link Score} database is guaranteed to be up to date when this
	 *   function is called and thus can be used by it.
	 *   Especially the Score database shall already have been updated to reflect the changes due to
	 *   the changed Trust.
	 *   The SubscriptionManager's callback is called before the Score database is updated because:
	 *   Its job is to deploy events to clients in the order they occurred, and if the Score events
	 *   were deployed before the Trust events then clients couldn't see the cause of the Score 
	 *   events before their effect which logically doesn't make sense.
	 *   However the existing implementation of this callback here don't care about this, and in
	 *   fact it does need the Scores, so this difference is hereby required.
	 * 
	 * - Is not called for all changes to attributes of the Trust but will only be called upon:
	 *   * {@link Trust#getValue()} changes.
	 *   * a Trust is created or deleted.
	 * 
	 * - Is NOT called if the {@link Trust#getTruster()} / {@link Trust#getTrustee()} is deleted,
	 *   {@link #storePreDeleteIdentityCommand(Identity)} is called for that case.
	 *   For the truster / trustee changing their type due to deleting / restoring an
	 *   {@link OwnIdentity} there are also separate callbacks.
	 * 
	 * ATTENTION: The passed {@link Trust} objects may be {@link Trust#clone()}s of the original
	 * objects. Hence when you want to do database queries using e.g. them, their
	 * {@link Trust#getTruster()} or {@link Trust#getTrustee()} you need to first re-query those
	 * objects from the database by their ID as the clones are unknown to the database.
	 * FIXME: Review implementations of this function for whether they are safe w.r.t. this.
	 * Alternatively, if {@link WebOfTrust#deleteWithoutCommit(Identity)} is the only function which
	 * passes a clone for newTrust, consider to change it to not call this callback as suggested by
	 * the comments there, and relax the "ATTENTION" to only be about oldTrust (which usually always
	 * be a clone because it represents a historical state).
	 * EDIT: The above FIXME was written before the case of truster/trustee changing their class
	 * between OwnIdentity and Identity was moved to separate callbacks, which also includes
	 * deleteWithoutCommit(Identity). Thus what it assumes about deleteWithoutCommit() likely
	 * doesn't apply anymore.
	 * 
	 * FIXME: It might make sense to change the JavaDoc of this callback here to not compare it
	 * to SubscriptionManager's callback anymore as the set of differences has already become too
	 * large.
	 * 
	 * FIXME: Rename to storeOwnTrustChanged...(), make callers only call it for Trusts where
	 * the truster is an OwnIdentity.
	 * They currently are the only ones which IdentityDownloaderFast is interested in, and it likely
	 * will stay as is for a long time.
	 * 
	 * Synchronization:
	 * This function is guaranteed to be called while the following locks are being held in the
	 * given order:
	 * synchronized(Instance of WebOfTrust)
	 * synchronized(WebOfTrust.getIdentityDownloaderController())
	 * synchronized(Persistent.transactionLock(WebOfTrust.getDatabase())) */
	void storeTrustChangedCommandWithoutCommit(Trust oldTrust, Trust newTrust);

	/**
	 * Called by {@link WebOfTrust} when we've downloaded the list of {@link Trust} values of a
	 * remote {@link Identity} and as a bonus payload have received an {@link EditionHint} for
	 * another {@link Identity} it has assigned a {@link Trust} to. An edition hint is the number of
	 * the latest {@link FreenetURI#getEdition()} of the given {@link Identity} as claimed by a
	 * remote identity. We can try to download the hint and if it is indeed downloadable, we are
	 * lucky - but it may very well be a lie. In that case, to avoid DoS, we must discard it and try
	 * the next lower hint we received from someone else.
	 * 
	 * NOTICE: EditionHints are almost passed as-is without much sanity checking. Thus:
	 * - Implementations MUST on their own check {@link WebOfTrust#shouldFetchIdentity(Identity)}
	 *   for the recipient of the hint.
	 * - Implementations MUST also decide on their own whether it is safe to use the given hints
	 *   with regards to their rules of the required trustworthiness of the hint's provider:  
	 *   The callers of this callback only check if the provider's
	 *   {@link WebOfTrust#getBestCapacity(Identity)} is >= {@link EditionHint#MIN_CAPACITY}
	 *   (because the EditionHint constructor would throw otherwise), the
	 *   {@link WebOfTrust#getBestScore(Identity)} is not checked, so even hints of distrusted
	 *   providers are passed.    
	 *   But it is advisable to stick to accepting all those hints when taking the concept of
	 *   "stability" of Score computation into consideration. For an explanation of this concept see
	 *   the documentation inside {@link XMLTransformer#importIdentity(FreenetURI,
	 *   java.io.InputStream)}.
	 * 
	 * The {@link Trust} and {@link Score} database is guaranteed to be up to date when this
	 * function is called and thus can be used by it for the aforementioned purposes.
	 * 
	 * Synchronization:
	 * This function is guaranteed to be called while the following locks are being held in the
	 * given order:
	 * synchronized(Instance of WebOfTrust)
	 * synchronized(WebOfTrust.getIdentityDownloaderController())
	 * synchronized(Persistent.transactionLock(WebOfTrust.getDatabase())) */
	void storeNewEditionHintCommandWithoutCommit(EditionHint hint);

	/**
	 * Called by the {@link XMLTransformer} after it has imported a new edition of an
	 * {@link Identity} from the {@link IdentityFileQueue} (which has been added to the queue by an
	 * IdentityDownloader before).  
	 * The edition can be obtained from {@link Identity#getLastFetchedEdition()}.
	 * FIXME: What about OwnIdentitys, should the IdentityInserter call it so we don't download
	 * their editions after upload?
	 * 
	 * The {@link IdentityDownloader} interface JavaDoc requires IdentityDownloader implementations
	 * as a whole to be safe against loss of the output {@link IdentityFileQueue} upon restarts of
	 * WoT. This callback here exists to guarantee that as follows:  
	 * IdentityDownloader implementations should **not** change the
	 * {@link Identity#getLastFetchedEdition()} (and/or their own database which keep track of it)
	 * in the "onSuccess()" callbacks of fred (which are called when a download finishes, they then
	 * typically hand it to the IdentityFileQueue).  
	 * Instead, onSuccess() shall only be used to store the IdentityFile to the IdentityFileQueue,
	 * and the database update to mark an edition as downloaded shall be deferred to this callback
	 * here.  
	 * Then loss of the queue contents will keep the {@link Identity#getLastFetchedEdition()}
	 * (and/or the downloader's database) in a state which shows that the edition wasn't downloaded
	 * yet and thus the IdentityDownloader will download it again.
	 * To ensure already downloaded editions are not downloaded again in between onSuccess() and
	 * onNewEditionImported() implementations should use
	 * {@link IdentityFileQueue#containsAnyEditionOf(FreenetURI)} in their download scheduler to not
	 * start a download for an Identity if that function returns true and thereby indicates that
	 * they've already downloaded an edition of that Identity.   
	 * See the large documentation inside of {@link IdentityDownloaderSlow#onSuccess(
	 * freenet.client.FetchResult, freenet.client.async.ClientGetter)} for why this whole design
	 * pattern is a good idea.
	 * As a side effect this design pattern also ensures collaboration among IdentityDownloaders:  
	 * If one implementation downloads an edition, other implementations running in parallel may
	 * avoid trying to download the same edition again.
	 * 
	 * Synchronization:
	 * This function is guaranteed to be called while the following locks are being held in the
	 * given order:
	 * synchronized(Instance of WebOfTrust)
	 * synchronized(WebOfTrust.getIdentityDownloaderController())
	 * synchronized(Persistent.transactionLock(WebOfTrust.getDatabase())) */
	@NeedsTransaction void onNewEditionImported(Identity identity);

	/**
	 * ATTENTION: For debugging purposes only.
	 * 
	 * Returns the effective state of whether the downloader will download an {@link Identity}
	 * = returns what was last instructed to this downloader using all the callbacks in this
	 * interface.
	 *
	 * Synchronization:
	 * This function is guaranteed to be called while the following locks are being held in the
	 * given order:
	 * synchronized(Instance of WebOfTrust)
	 * synchronized(WebOfTrust.getIdentityDownloaderController())
	 * synchronized(Persistent.transactionLock(WebOfTrust.getDatabase())) */
	boolean getShouldFetchState(Identity identity);

	/**
	 * ATTENTION: For debugging purposes only.
	 * 
	 * Should delete all queued commands about starting/stopping downloads.
	 * I.e. it should delete the whole db4o database table of the implementation.
	 * TODO: Code quality: Hence rename to e.g. deleteDatabaseContents().
	 * 
	 * This is to allow {@link WebOfTrust#checkForDatabaseLeaks()} to test for whether there are
	 * any database leaks in the subsystems of WoT.
	 * 
	 * Synchronization:
	 * This function is NOT called with any locks held! It has to create a database transaction of
	 * its own as follows, while taking the thereby listed locks:
	 * <code>
	 * synchronized(Instance of WebOfTrust) {
	 * synchronized(WebOfTrust.getIdentityDownloaderController()) {
	 * synchronized(Persistent.transactionLock(WebOfTrust.getDatabase())) {
	 *     try {
	 *        deleteTheCommands();
	 *        Persistent.checkedCommit(database, this);
	 *     } catch(RuntimeException e) {
	 *        Persistent.checkedRollbackAndThrow(database, this, e);
	 *     }
	 * }}}
	 * </code> */
	void deleteAllCommands();

	/**
	 * Schedules processing of any commands which have been enqueued using the store...() functions,
	 * with a delay of 0.
	 * This is useful because normally commands are enqueued for batch processing with a delay of
	 * e.g. 1 minute (to ensure multiple commands enqueued in a short timespan get processed at once
	 * to keep overhead low) but certain actions in the user interface should be processed ASAP.
	 * 
	 * NOTICE: You do **not** need to call any function to trigger regular, non-immediate command
	 * processing. All functions for storing commands do that on their own. */
	public void scheduleImmediateCommandProcessing();

}
