package plugins.WebOfTrust.util;

import static java.lang.Double.isInfinite;
import static java.lang.Double.isNaN;
import static java.lang.Math.abs;
import static java.lang.Math.max;

public final class MathUtil {
	/**
	 * Tests if the two numbers are equals up to the given percentage.
	 * E.g. if you specify an accuracy of 90%, this allows up to 10% of the larger number as
	 * difference.
	 * The percentage should be > 80 and < 100.
	 * 
	 * @throws ArithmeticException If either of the numbers is NaN, infinite or a positive/negative
	 *     maximal double value.
	 *     This is to ensure this is suitable for use in unit tests: When checking if results of two
	 *     calculation match it should be ensured that the calculations actually did produce
	 *     results, not just floating point error values. */
	public static boolean equalsApprox(double a, double b, double percentage) {
		assert(percentage > 80);
		assert(percentage < 100);

		if(isNaN(a) || isNaN(b))
			throw new ArithmeticException();
		
		if(isInfinite(a) || isInfinite(b))
			throw new ArithmeticException();
		
		if(a == Double.MAX_VALUE || b == Double.MAX_VALUE)
			throw new ArithmeticException();
		
		if(a == Double.MIN_VALUE || b == Double.MIN_VALUE)
			throw new ArithmeticException();
		
		if(a == b)
			return true;
		
		double delta = abs(a - b);
		double size = max(abs(a), abs(b));
		double errorPercentage = 100d - percentage;
		return delta <= (errorPercentage/100d) * size;
	}
}