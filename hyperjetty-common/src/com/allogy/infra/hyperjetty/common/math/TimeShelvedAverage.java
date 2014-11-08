package com.allogy.infra.hyperjetty.common.math;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * User: robert
 * Date: 2012/12/11
 * Time: 1:54 PM
 */
public
class TimeShelvedAverage
{
	private final
	long period;

	private final
	int count;

	private final
	RunningAverage runningAverage;

	public
	TimeShelvedAverage(long period, int count)
	{
		this.period = period;
		this.count = count;
		this.runningAverage = new TimeDecayingRunningAverage(0, period*count, 0.0, Double.MAX_VALUE);
		this.timeShiftingGate = new AtomicLong(System.currentTimeMillis());
	}

	private final
	AtomicLong timeShiftingGate;

	private
	Long alphaTime;

	private final
	AtomicInteger countSinceAlphaTime = new AtomicInteger();

	public /* NOT synchronized (!) */
	void lowContentionIncrement()
	{
		countSinceAlphaTime.incrementAndGet();

		long now = System.currentTimeMillis();
		long lastTimeShift = timeShiftingGate.get();

		//NB: falls into synchronize block in general (and *at*most*) once every millisecond...
		if (lastTimeShift != now && timeShiftingGate.compareAndSet(lastTimeShift, now))
		{
			maybeDoTimeShift(now, 1);
		}
	}

	private synchronized
	void maybeDoTimeShift(long now, int incremented)
	{
		if (alphaTime == null || now < alphaTime)
		{
			alphaTime = now;
		}
		else
		if (now > alphaTime + period + period)
		{
			//NB: inaccurate, and racy... but we know that we have at least one (that triggered this), and a slow rate (skipped interval)
			long plusOne=countSinceAlphaTime.getAndSet(incremented);

			runningAverage.report(plusOne-1, alphaTime+period);

			alphaTime = now;
		}
		else
		if (now > alphaTime + period)
		{
			alphaTime = now;

			runningAverage.report(countSinceAlphaTime.getAndSet(0), now);
		}
	}

	public synchronized
	double getValue()
	{
		final
		long now = System.currentTimeMillis();

		maybeDoTimeShift(now, 0);

		return runningAverage.currentValue();
	}

	public synchronized
	void reset()
	{
		alphaTime = null;
		countSinceAlphaTime.set(0);
		runningAverage.reset();
	}
}