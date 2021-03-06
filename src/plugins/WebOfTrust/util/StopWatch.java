/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.util;

import static freenet.support.TimeUtil.formatTime;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

/** Utility class for measuring execution time of code. */
public final class StopWatch {

	private final long mStartTime = System.nanoTime();

	/** {@link #mStartTime} plus the runtime of the StopWatch.  
	 *  Null if {@link #stop()} was not called yet.
	 * 
	 *  NOTICE: This does NOT accurately represent the absolute point in time at which the
	 *  StopWatch was stopped! It merely can be used to accurately compute the delta
	 *  `mStopTime - {@link #mStartTime}` as a means to compute the runtime of the StopWatch.  
	 *  See {@link #add(StopWatch)} for the reason.
	 * 
	 *  We use Long so we can flag the StopWatch as "empty" using a value of null.  
	 *  We do not use long and "-1" instead of null because {@link System#nanoTime()} does not
	 *  specify whether the return value is always positive. */
	private Long mStopTime = null;


	public void stop() {
		mStopTime = System.nanoTime();
	}

	public void stopIfNotStoppedYet() {
		if(!wasStopped())
			stop();
	}

	public boolean wasStopped() {
		return mStopTime != null;
	}

	public long getNanos() {
		stopIfNotStoppedYet();
		return mStopTime - mStartTime;
	}

	public void divideNanosBy(long divisor) {
		stopIfNotStoppedYet();
		mStopTime = mStartTime + (mStopTime - mStartTime) / divisor;
	}

	public String toString() {
		return formatTime(NANOSECONDS.toMillis(getNanos()), 3, true);
	}

	public void add(StopWatch other) {
		stopIfNotStoppedYet();
		mStopTime += other.getNanos();
	}

}
