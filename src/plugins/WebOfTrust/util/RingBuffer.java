package plugins.WebOfTrust.util;

import java.io.Serializable;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Iterator;

/***
 * A wrapper around class {@link ArrayDeque} which automatically obeys a size limit.
 * Once the limit is reached adding an element to the tail will remove the head element and vice
 * versa. */
public class RingBuffer<T> implements Cloneable, Iterable<T>, Serializable {

	private static final long serialVersionUID = 1L;

	private final ArrayDeque<T> mQueue;

	private final int mSizeLimit;


	public RingBuffer(int sizeLimit) {
		if(sizeLimit < 1)
			throw new IllegalArgumentException("sizeLimit is < 1: " + sizeLimit);
		
		mQueue = new ArrayDeque<T>();
		mSizeLimit = sizeLimit;
	}

	/** Copy-constructor for {@link #clone()}. */
	private RingBuffer(RingBuffer<T> original) {
		mQueue = original.mQueue.clone();
		mSizeLimit = original.mSizeLimit;
	}

	public final T addFirst(T element) {
		T result = null;
		
		if(mQueue.size() >= mSizeLimit)
			result = mQueue.removeLast();
		
		mQueue.addFirst(element);
		
		return result;
	}

	public final T addLast(T element) {
		T result = null;
		
		if(mQueue.size() >= mSizeLimit)
			result = mQueue.removeFirst();
		
		mQueue.addLast(element);
		
		return result;
	}

	public final boolean addAll(Collection<? extends T> elements) {
		boolean result = mQueue.addAll(elements);
		
		while(mQueue.size() > mSizeLimit)
			mQueue.removeFirst();
		
		return result;
	}

	public final void clear() {
		mQueue.clear();
	}

	public final T peekFirst() {
		return mQueue.peekFirst();
	}

	public final T peekLast() {
		return mQueue.peekLast();
	}

	public final int size() {
		return mQueue.size();
	}

	public final int sizeLimit() {
		return mSizeLimit;
	}

	@Override public final RingBuffer<T> clone() {
		return new RingBuffer<>(this);
	}

	@Override public final Iterator<T> iterator() {
		return mQueue.iterator();
	}

	public final T[] toArray(T[] output) {
		return mQueue.toArray(output);
	}

	@Override public final int hashCode() {
		throw new UnsupportedOperationException("Not implemented yet!");
	}

	@Override public final boolean equals(Object obj) {
		throw new UnsupportedOperationException("Not implemented yet!");
	}
}
