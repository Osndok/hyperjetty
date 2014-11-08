package com.allogy.infra.hyperjetty.common.math;

import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Time decaying running average.
 * <p/>
 * Decay factor = 0.5 ^ (interval / halflife).
 * <p/>
 * So if the interval is exactly the half-life then reporting 0 will halve the value.
 * <p/>
 * Note that the older version has a half life on the influence of any given report without taking
 * into account the fact that reports persist and accumulate. :)
 * <p/>
 * -
 * <p/>
 * Extracted from FLOSS project: "Freenet"
 */
public
class TimeDecayingRunningAverage implements RunningAverage
{

	private static final long serialVersionUID = -1;

	@Override
	public final
	TimeDecayingRunningAverage clone()
	{
		return new TimeDecayingRunningAverage(this);
	}

	final
	double halfLife;

	double curValue;

	long    lastReportTime;
	long    createdTime;
	long    totalReports;
	boolean started;
	double  defaultValue;
	double  minReport;
	double  maxReport;
	boolean logDEBUG;
	//private final TimeSkewDetectorCallback timeSkewCallback;

	@Override
	public
	String toString()
	{
		long now = System.currentTimeMillis();
		synchronized (this)
		{
			return super.toString() + ": currentValue=" + curValue + ", halfLife=" + halfLife +
					   ", lastReportTime=" + (now - lastReportTime) +
					   "ms ago, createdTime=" + (now - createdTime) +
					   "ms ago, totalReports=" + totalReports + ", started=" + started +
					   ", defaultValue=" + defaultValue + ", min=" + minReport + ", max=" + maxReport;
		}
	}

	/**
	 * @param defaultValue
	 * @param halfLife
	 * @param min
	 * @param max
	 */
	public
	TimeDecayingRunningAverage(
								  double defaultValue, long halfLife,
								  double min, double max
	)
	{
		curValue = defaultValue;
		this.defaultValue = defaultValue;
		started = false;
		this.halfLife = halfLife;
		createdTime = lastReportTime = System.currentTimeMillis();
		this.minReport = min;
		this.maxReport = max;
		totalReports = 0;
		logDEBUG = false;
	}

	public
	void reset()
	{
		totalReports=0;
	}

	/**
	 * @param a
	 */
	public
	TimeDecayingRunningAverage(TimeDecayingRunningAverage a)
	{
		this.createdTime = a.createdTime;
		this.defaultValue = a.defaultValue;
		this.halfLife = a.halfLife;
		this.lastReportTime = a.lastReportTime;
		this.maxReport = a.maxReport;
		this.minReport = a.minReport;
		this.started = a.started;
		this.totalReports = a.totalReports;
		this.curValue = a.curValue;
	}

	/**
	 * @return
	 */
	//@Override
	public synchronized
	double currentValue()
	{
		return curValue;
	}

	/**
	 * @param d
	 */
	//@Override
	public
	void report(double d, long now)
	{
		synchronized (this)
		{
			if (d < minReport)
			{
				return;
			}

			if (d > maxReport)
			{
				return;
			}

			if (Double.isInfinite(d) || Double.isNaN(d))
			{
				return;
			}

			totalReports++;

			if (!started)
			{
				curValue = d;
				started = true;
				//if(logDEBUG)
				//Logger.debug(this, "Reported "+d+" on "+this+" when just started");
			}
			else
			if (lastReportTime != -1)
			{ // might be just serialized in
				long thisInterval =
					now - lastReportTime;
				long uptime = now - createdTime;
				if (thisInterval < 0)
				{
					//Logger.error(this, "Clock (reporting) went back in time, ignoring report: "+now+" was "+lastReportTime+" (back "+(-thisInterval)+"ms)");
					lastReportTime = now;
					//if(timeSkewCallback != null)
					//    timeSkewCallback.setTimeSkewDetectedUserAlert();
					return;
				}
				double thisHalfLife = halfLife;
				if (uptime < 0)
				{
					//Logger.error(this, "Clock (uptime) went back in time, ignoring report: "+now+" was "+createdTime+" (back "+(-uptime)+"ms)");
					//if(timeSkewCallback != null)
					//    timeSkewCallback.setTimeSkewDetectedUserAlert();
					return;
					// Disable sensitivity hack.
					// Excessive sensitivity at start isn't necessarily a good thing.
					// In particular it makes the average inconsistent - 20 reports of 0 at 1s intervals have a *different* effect to 10 reports of 0 at 2s intervals!
					// Also it increases the impact of startup spikes, which then take a long time to recover from.
					//} else {
					//double oneFourthOfUptime = uptime / 4D;
					//if(oneFourthOfUptime < thisHalfLife) thisHalfLife = oneFourthOfUptime;
				}

				if (thisHalfLife == 0)
				{
					thisHalfLife = 1;
				}
				double changeFactor =
					Math.pow(0.5, (thisInterval) / thisHalfLife);
				double oldCurValue = curValue;
				curValue = curValue * changeFactor /* close to 1.0 if short interval, close to 0.0 if long interval */
							   + (1.0 - changeFactor) * d;
				// FIXME remove when stop getting reports of wierd output values
				if (curValue < minReport || curValue > maxReport)
				{
					//Logger.error(this, "curValue="+curValue+" was "+oldCurValue+" - out of range");
					curValue = oldCurValue;
				}
                /*
                if(logDEBUG)
                    Logger.debug(this, "Reported "+d+" on "+this+": thisInterval="+thisInterval+
                            ", halfLife="+halfLife+", uptime="+uptime+", thisHalfLife="+thisHalfLife+
                            ", changeFactor="+changeFactor+", oldCurValue="+oldCurValue+
                            ", currentValue="+currentValue()+
                            ", thisInterval="+thisInterval+", thisHalfLife="+thisHalfLife+
                            ", uptime="+uptime+", changeFactor="+changeFactor);
                */
			}
			lastReportTime = now;
		}
	}

	/**
	 * @param d
	 */
	//@Override
	public
	void report(long d, long now)
	{
		report((double) d, now);
	}

	//@Override
	public
	double valueIfReported(double r)
	{
		throw new UnsupportedOperationException();
	}

	//@Override
	public synchronized
	long countReports()
	{
		return totalReports;
	}

	/**
	 * @return
	 */
	public synchronized
	long lastReportTime()
	{
		return lastReportTime;
	}

}
