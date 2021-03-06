package plugins.WebOfTrust.util.plotting;

import static java.lang.Math.PI;
import static java.lang.Math.cos;
import static java.lang.Math.sin;
import static java.util.Arrays.asList;
import static java.util.concurrent.TimeUnit.HOURS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static plugins.WebOfTrust.util.CollectionUtil.array;
import static plugins.WebOfTrust.util.CollectionUtil.arrayList;
import static plugins.WebOfTrust.util.MathUtil.equalsApprox;
import static plugins.WebOfTrust.util.Pair.pair;
import static plugins.WebOfTrust.util.plotting.XYChartUtils.differentiate;
import static plugins.WebOfTrust.util.plotting.XYChartUtils.getTimeBasedPlotPNG;
import static plugins.WebOfTrust.util.plotting.XYChartUtils.movingAverage;
import static plugins.WebOfTrust.util.plotting.XYChartUtils.multiplyY;

import java.util.Iterator;
import java.util.List;

import org.junit.Test;

import freenet.l10n.BaseL10n;
import plugins.WebOfTrust.AbstractJUnit4BaseTest;
import plugins.WebOfTrust.WebOfTrust;
import plugins.WebOfTrust.util.RingBuffer;
import plugins.WebOfTrust.util.Pair;
import plugins.WebOfTrust.util.plotting.XYChartUtils.TimeChart;

public final class XYChartUtilsTest extends AbstractJUnit4BaseTest {
	
	@Test public void testTimeChartConstructors() {
		TimeChart<Double> c1 = new TimeChart<>(1);
		assertEquals(1, c1.sizeLimit());
		
		RingBuffer<Pair<Long, Long>> data = new RingBuffer<>(2);
		data.addLast(pair(SECONDS.toMillis(10), 123l));
		data.addLast(pair(SECONDS.toMillis(20), 345l));
		long t0 = SECONDS.toMillis(1);
		TimeChart<Long> c2 = new TimeChart<Long>(data, t0);
		assertEquals(2, c2.size());
		assertEquals(2, c2.sizeLimit());
		assertEqualsApprox(9,  c2.peekFirst().x, 99.99d);
		assertEquals(123l,     c2.peekFirst().y.longValue());
		assertEqualsApprox(19, c2.peekLast().x, 99.99d);
		assertEquals(345l,     c2.peekLast().y.longValue());
	}

	/**
	 * Pseudo-test for {@link XYChartUtils#getTimeBasedPlotPNG(freenet.l10n.BaseL10n, String,
	 * String, String, String, java.util.Collection)}:
	 * We cannot easily test if the rendered image makes sense, but we can test if generating it at
	 * least does now throw. */
	@Test public void testGetTimeBasedPlotPNG() {
		BaseL10n l10n = constructEmptyWebOfTrust().getBaseL10n();
		TimeChart<Integer> c = new TimeChart<>(2);
		// The underlying XChart library won't accept an empty dataset so add one element.
		c.addLast(pair(1d, 1));
		
		// It is fine to use arbitrary strings which don't exist as l10n keys in the l10n files, the
		// l10n code will consider them as untranslated.
		byte[] png = getTimeBasedPlotPNG(l10n, "title", "xLabelHours", "xLabelMinutes", "yLabel",
			arrayList(c));
		assertNotEquals(0, png.length);
		// TODO: Code quality: Use Freenet's PNGFilter to test if the image is valid.
		// At first glance it currently does not seem possible since the constructor of PNGFilter is
		// not public, but I do feel like remembering that fred plugins can filter images to e.g.
		// display avatars in Freetalk.
		
		// Test code path for switching the x-axis label to hours.
		c.addLast(pair(1d + HOURS.toSeconds(2), 1));
		png = getTimeBasedPlotPNG(l10n, "title", "xLabelHours", "xLabelMinutes", "yLabel",
			arrayList(c));
		assertNotEquals(0, png.length);
		
		// Test code path for rendering multiple TimeCharts into one output
		TimeChart<Integer> c2 = new TimeChart<>(1);
		c2.addLast(pair(1d, 1));
		c2.setLabel("c2"); // Prevent XChart from complaining that the series name was already used
		png = getTimeBasedPlotPNG(l10n, "title", "xLabelHours", "xLabelMinutes", "yLabel",
			arrayList(c, c2));
		assertNotEquals(0, png.length);
	}

	@Test public void testMovingAverage() {
		TimeChart<Double> data = new TimeChart<>(128);
		assertEquals(0, movingAverage(data, 1).size());
		
		// Use the first N natural numbers as input for the average as their sum can easily be
		// calculated as:
		//     sum(1, 2, 3, ..., N) = N*(N+1) / 2
		// This equation is unnamed in English, it can be found by its German name
		// "Gaußsche Summenformel".
		for(int n = 1; n <= 15; ++n)
			data.addLast(pair((double) n, (double) -n));
		
		TimeChart<Double> average = movingAverage(data, 1);
		// For the first 16 elements only one average will be yielded as the minimum window size
		// is not only the given amount of seconds but also 16 elements.
		// We only added 15 yet so nothing should be yielded.
		assertEquals(0, average.size());
		data.addLast(pair(16d, -16d));
		average = movingAverage(data, 1);
		assertEquals(1, average.size());
		Pair<Double, Double> a1 = average.peekFirst();
		assertEqualsApprox( sumOfNumbers(16) / 16, a1.x, 99.999d);
		assertEqualsApprox(-sumOfNumbers(16) / 16, a1.y, 99.999d);
		
		TimeChart<Double> prevAverage;
		for(int n = 17; n <= 32; ++n) {
			data.addLast(pair((double) n, (double) -n));
			prevAverage = average;
			average = movingAverage(data, 1);
			assertEquals(prevAverage.size() + 1, average.size());
			
			// Check if the previous average values are all at the beginning of the new one.
			// TODO: Code quality: Java 8: See testMultiplyY().
			Iterator<Pair<Double, Double>> iExp = prevAverage.iterator();
			Iterator<Pair<Double, Double>> iAct = average.iterator();
			for(int i=0; i < prevAverage.size(); ++i) {
				Pair<Double, Double> exp = iExp.next();
				Pair<Double, Double> act = iAct.next();
				assertEqualsApprox(exp.x, act.x, 99.999d);
				assertEqualsApprox(exp.y, act.y, 99.999d);
			}
			
			// Check the actual new average value
			Pair<Double, Double> a = average.peekLast();
			// The window size of movingAverage() is at least 1 second, which we fulfill by spacing
			// elements by 1, and at least 16 elements. So every iteration of our loop the element
			// n-16 falls out. sumOfNumbers(a, b) excludes all up to including a to match this.
			assertEqualsApprox( sumOfNumbers(n-16, n) / 16, a.x, 99.999d);
			assertEqualsApprox(-sumOfNumbers(n-16, n) / 16, a.y, 99.999d);
		}
		
		// Now that we sufficiently tested the minimum window size of 16 elements we yet have to
		// test the minimum window size in seconds as passed to movingAverage().
		// To do so we raise the window size enough to ensure it covers precisely the timespan
		// of all elements so they ought to all be put into one output average value to ensure the
		// output value meets the window size constraint.
		// Our 32 elements start at time value x=1 second and end at x=32 seconds, so they span
		// 31 seconds and hence the window needs to be 31 seconds.
		assert(data.size() == 32);
		assert(equalsApprox(31, data.peekLast().x - data.peekFirst().x, 99.999d));
		average = movingAverage(data, 31);
		assertEquals(1, average.size());
		Pair<Double, Double> a = average.peekLast();
		assertEqualsApprox( sumOfNumbers(32) / 32, a.x, 99.999d);
		assertEqualsApprox(-sumOfNumbers(32) / 32, a.y, 99.999d);
	}

	/** @see #testSumOfNumbers() */
	private static double sumOfNumbers(int n) {
		assert(n >= 0);
		int enumerator = n*(n+1);
		assert(enumerator % 2 == 0) : "Integer division before casting is OK";
		return enumerator / 2;
	}

	/** @see #testSumOfNumbers() */
	private static double sumOfNumbers(int a, int b) {
		assert(b > a);
		return sumOfNumbers(b) - sumOfNumbers(a);
	}
	
	@Test public void testSumOfNumbers() {
		assertEqualsApprox(1 + 2 + 3 + 4 + 5, sumOfNumbers(5),    99.999d);
		assertEqualsApprox(            4 + 5, sumOfNumbers(3, 5), 99.999d);
	}

	@Test public void testDifferentiate() {
		TimeChart<Double> sinus = new TimeChart<>(1024);
		
		for(int i = 0; i < 1024; ++i) {
			double x = (2*PI / 1024) * i;
			double y = sin(x);
			sinus.addLast(pair(x, y));
			assertEquals(i+1, sinus.size());
		}
		
		TimeChart<Double> differentials = differentiate(sinus);
		assertEquals(sinus.size() - 1, differentials.size());
		
		Iterator<Pair<Double, Double>> iter = differentials.iterator();
		for(int i = 0; i < 1024-1; ++i) {
			double x1 = (2*PI / 1024) * i;
			double x2 = (2*PI / 1024) * (i+1);
			double x = (x1+x2) / 2;
			double y = cos(x); // d/dx sin(x) = cos(x)
			
			Pair<Double, Double> p = iter.next();
			assertEqualsApprox(x, p.x, 99.999d);
			assertEqualsApprox(y, p.y, 99.999d);
		}
		
		// Test special cases:
		// - Trying to differentiate a single value, which is not possible
		TimeChart<Double> data = new TimeChart<>(1);
		data.addLast(pair(1d, 1d));
		assertEquals(0, differentiate(data).size());
		// - dx == 0, which would result in divion by zero if not handled properly.
		data = new TimeChart<>(2);
		data.addLast(pair(0d, 0d));
		data.addLast(pair(0d, 1d));
		assertEquals(0, differentiate(data).size());
	}

	@Test public void testMultiplyY() {
		// TODO: Code quality: Java 8: Use "var" to shorten this and the other generics here
		// - hence the bloatedness of this function for such a simple test, to remind me of Java 8
		// patterns to learn.
		TimeChart<Double> input = new TimeChart<>(5);
		input.addAll(asList(array(
			pair(1d, -2.1d),
			pair(2d, -1.1d),
			pair(3d,  0.0d),
			pair(4d,  1.1d),
			pair(5d,  2.1d)
		)));
		TimeChart<Double> actual = multiplyY(input, -10);
		List<Pair<Double, Double>> expected = asList(array(
			pair(1d,  21d),
			pair(2d,  11d),
			pair(3d,   0d),
			pair(4d, -11d),
			pair(5d, -21d)
		));
		
		assertEquals(5, input.size());
		assertEquals(5, actual.size());
		// TODO: Code quality: Java 8: Lambda expressions would shorten this a lot:
		// https://stackoverflow.com/a/37612232
		// A pattern which will be worth learning for any occasion of iterating over two lists.
		Iterator<Pair<Double, Double>> iExp = expected.iterator();
		Iterator<Pair<Double, Double>> iAct = actual.iterator();
		for(int i=0; i < 5; ++i) {
			Pair<Double, Double> exp = iExp.next();
			Pair<Double, Double> act = iAct.next();
			assertEqualsApprox(exp.x, act.x, 99.999d);
			assertEqualsApprox(exp.y, act.y, 99.999d);
		}
	}

	@Override protected WebOfTrust getWebOfTrust() {
		return null;
	}

}
