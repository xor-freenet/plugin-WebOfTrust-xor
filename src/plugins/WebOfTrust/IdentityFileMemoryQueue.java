/* This code is part of WoT, a plugin for Freenet. It is distributed
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.LinkedList;

import freenet.keys.FreenetURI;
import freenet.support.CurrentTimeUTC;
import freenet.support.Logger;
import plugins.WebOfTrust.util.Pair;
import plugins.WebOfTrust.util.jobs.BackgroundJob;

/**
 * {@link IdentityFileQueue} implementation which stores the files in memory instead of on disk.<br>
 * Order of the files is preserved in a FIFO manner.<br><br>
 * 
 * This implementation aims at being used in unit tests only. Thus, in comparison to the
 * {@link IdentityFileDiskQueue} which WOT actually uses, it has the following disadvantages:<br>
 * - It doesn't deduplicate editions. See {@link IdentityFileQueue} for what that means.<br>
 * - It doesn't watch its memory usage and thus on fast Freenet nodes might cause OOM.<br>
 * - It doesn't use the {@link Logger}, you need to instead enable assert() in your JVM.<br><br>
 * 
 * TODO: Performance: Add configuration option to allow users to make their WOT use this instead
 * of the default {@link IdentityFileDiskQueue}. Be sure to resolve the above disadvantages before,
 * and to warn users that they only should use it if they have lots of memory. */
final class IdentityFileMemoryQueue implements IdentityFileQueue {

	/** This number of files is calculated to result in at most ~ 128 MiB of memory usage
	 *  considering the maximum size of each file.  
	 *  @see IdentityFileQueue#getSizeSoftLimit() */
	private static final int SIZE_SOFT_LIMIT_FILES
		= (128*1024*1024) / XMLTransformer.MAX_IDENTITY_XML_BYTE_SIZE;

	private final LinkedList<IdentityFile> mQueue = new LinkedList<IdentityFile>();

	private final IdentityFileQueueStatistics mStatistics = new IdentityFileQueueStatistics();

	private BackgroundJob mEventHandler;


	@Override public synchronized boolean containsAnyEditionOf(FreenetURI identityFileURI) {
		// FIXME: Performance: Investigate the impact upon tests of this.
		Logger.warning(this,
			"IdentityFileMemoryQueue.containsAnyEditionOf() is slow, only use it in unit tests! " +
			"Use IdentityFileDiskQueue in normal operation instead.", new RuntimeException());
		
		// FIXME: In opposite to IdentityFileDiksQueue.contains*(), this will NOT return true while
		// the file is being processed! Determine if this causes any breakage of tests.
		// EDIT: Recently IdentityFileStreamWrapper has been added to IdentityFileQueue, and poll()
		// has been changed to return an instance of that. This will allow us to fix the issue
		// easily because the goal behind IdentityFileStreamWrapper **is** to allow contains*() to
		// keep returning true while the file is being processed.
		// To implement this we merely have to change the mock IdentityFileStreamWrapper
		// implementation we return in poll() to actually do its job.
		// This also allows us to fix the different behavior among *DiskQueue and *MemoryQueue which
		// the JavaDoc of IdentityFileQueueStatistics talks about.
		for(IdentityFile f : mQueue) {
			if(f.getURI().equalsKeypair(identityFileURI))
				return true;
		}
		
		return false;
	}

	@Override public synchronized void add(IdentityFileStream file) {
		try {
			mQueue.addLast(IdentityFile.read(file));
			++mStatistics.mQueuedFiles;
			
			if(mEventHandler != null)
				mEventHandler.triggerExecution();
			else {
				// The IdentityFetcher might fetch files during its start() already, and call this
				// function to enqueue fetched files. However, IdentityFileProcessor.start(), which
				// would register it as the event handler which is missing here, is called *after*
				// IdentityFetcher.start(). This is to prevent identity file processing from slowing
				// down WoT startup.
				// Thus having not an event handler yet is not an error, which is why the below
				// assert() is commented out.
				// TODO: Code quality: Once https://bugs.freenetproject.org/view.php?id=6674 has
				// been fixed, we could split IdentityFileProcessor.start() into register() and
				// start(): Register would be what start() was previously, i.e. register the event
				// handler so we're not missing it here anymore. start() would be what was demanded
				// by the bugtracker entry, i.e. enable IdentityFileProcessor.triggerExecution().
				// IdentityFileProcessor.register() could then be called before
				// IdentityFetcher.start(), and IdentityFileProcessor.start() afterwards. It then
				// wouldn't matter if register() is called before IdentityFetcher.start():
				// This IdentityFileMemoryQueue could not cause the processor to process the files
				// which the IdentityFetcher adds, as IdentityFileProcessor.triggerExecution() would
				// only work after start().
				// So overall, we could then enable this assert() for robustness.
				/*
				assert(false);
				*/
			}
		} catch(RuntimeException e) {
			++mStatistics.mFailedFiles;
			assert(false) : e;
			throw e;
		} catch(Error e) { // TODO: Java 7: Merge with above to catch(RuntimeException | Error e)
			++mStatistics.mFailedFiles;
			assert(false) : e;
			throw e;
		} finally {
			++mStatistics.mTotalQueuedFiles;
			mStatistics.mTimesOfQueuing.addLast(
				new Pair<>(CurrentTimeUTC.getInMillis(),
					// IdentityFileMemoryQueue is not persisted across sessions, so subtracting
					// mLefoverFilesOfLastSession is not necessary - but let's do it anyway in case
					// the class is someday amended with persistence.
					mStatistics.mTotalQueuedFiles - mStatistics.mLeftoverFilesOfLastSession));
			assert(checkConsistency());
		}
	}

	@Override public synchronized IdentityFileStreamWrapper poll() {
		try {
			IdentityFile file;
			
			while((file = mQueue.pollFirst()) != null) {
				try {
					final IdentityFileStream ifs = new IdentityFileStream(
						file.getURI(), new ByteArrayInputStream(file.mXML));

					IdentityFileStreamWrapper result = new IdentityFileStreamWrapper() {
						@Override public IdentityFileStream getIdentityFileStream() {
							return ifs;
						}

						@Override public void close() throws IOException {
							ifs.mXMLInputStream.close();
						}
					};
					
					++mStatistics.mFinishedFiles;
					
					return result;
				} catch(RuntimeException e) {
					++mStatistics.mFailedFiles;
					assert(false) : e;
					continue;
				} catch(Error e) {
					// TODO: Java 7: Merge with above to catch(RuntimeException | Error e)
					++mStatistics.mFailedFiles;
					assert(false) : e;
					continue;
				} finally {
					--mStatistics.mQueuedFiles;
				}
			}
				
			return null; // Queue is empty
		} finally {
			assert(checkConsistency());
		}
	}

	@Override public synchronized int getSize() {
		assert(mStatistics.mQueuedFiles == mQueue.size());
		return mStatistics.mQueuedFiles;
	}

	@Override public int getSizeSoftLimit() {
		return SIZE_SOFT_LIMIT_FILES;
	}

	@Override public synchronized void registerEventHandler(BackgroundJob handler) {
		if(mEventHandler != null) {
			throw new UnsupportedOperationException(
				"Support for more than one event handler is not implemented yet.");
		}
		
		mEventHandler = handler;
		
		if(mQueue.size() != 0)
			mEventHandler.triggerExecution();
	}

	@Override public synchronized IdentityFileQueueStatistics getStatistics() {
		assert(checkConsistency());
		return mStatistics.clone();
	}
	
	@Override public IdentityFileQueueStatistics getStatisticsOfLastSession() throws IOException {
		throw new IOException("IdentityFileMemoryQueue does not store anything to disk!");
	}

	private synchronized boolean checkConsistency() {
		return
			   mStatistics.checkConsistency()
			&& mStatistics.mDeduplicatedFiles == 0
			&& mStatistics.mProcessingFiles == 0
			&& mStatistics.mQueuedFiles == mQueue.size();
	}

	@Override public void stop() { }

}
