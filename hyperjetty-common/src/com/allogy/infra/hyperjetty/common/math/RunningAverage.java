package com.allogy.infra.hyperjetty.common.math;

import java.io.Serializable;

/**
 * Extracted from opensource project "Freenet"
 * Date: 2012/12/11
 * Time: 1:44 PM
 */
public interface RunningAverage extends Serializable {

	/**
	 *
	 * @return
	 */
	public RunningAverage clone();

	/**
	 *
	 * @return
	 */
	public double currentValue();

	/**
	 *
	 * @param d
	 */
	public void report(double d);

	/**
	 *
	 * @param d
	 */
	public void report(long d);

	/**
	 * Get what currentValue() would be if we reported some given value
	 * @param r the value to mimic reporting
	 * @return the output of currentValue() if we were to report r
	 */
	public double valueIfReported(double r);

	/**
	 * @return the total number of reports on this RunningAverage so far.
	 * Used for weighted averages, confidence/newbieness estimation etc.
	 */
	public long countReports();

	void reset();
}
