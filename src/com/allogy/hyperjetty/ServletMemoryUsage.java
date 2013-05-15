package com.allogy.hyperjetty;

/**
 * User: robert
 * Date: 2013/05/15
 * Time: 4:25 PM
 */
class ServletMemoryUsage
{

    private Triple heap;
    private Triple permGen;

    public
    void setHeapStats(long heapUsed, long heapMax, int heapPercentage)
    {
        this.heap=new Triple(heapUsed, heapMax, heapPercentage);
    }

    public
    void setPermGenStats(long permUsed, long permMax, int permPercentage)
    {
        this.permGen=new Triple(permUsed, permMax, permPercentage);
    }

    public
    String getHeapSummary()
    {
        return summarize(heap);
    }

    public
    String getPermGenSummary()
    {
        return summarize(permGen);
    }

    private static
    String summarize(Triple triple)
    {
        if (triple==null)
        {
            return "   - N/A -  ";
        }
        else
        {
            return triple.toString();
        }
    }

    private class Triple
    {
        private final long used;
        private final long max;
        private final int percentage;

        private Triple(long used, long max, int percentage)
        {
            this.used = used;
            this.max = max;
            this.percentage = percentage;
        }

        @Override
        public
        String toString()
        {
            /*
            Should always be 12 characters long:
            "XXX% of YYYm"
            As such, we use powers of 10 (i.e. divide by 1000) so we won't end up with 1013 (which is four characters).
             */
            long total=max;
            char magnitude='b';
            if (total > 1000) {
                total/=1000;
                magnitude='k';
            }
            if (total > 1000) {
                total/=1000;
                magnitude='m';
            }
            if (total > 1000) {
                total/=1000;
                magnitude='g';
            }
            if (total > 1000) {
                total/=1000;
                magnitude='t';
            }
            if (total > 1000) {
                total/=1000;
                magnitude='p';
            }
            if (total > 1000) {
                total/=1000;
                magnitude='x';
            }
            return String.format("%3d%% of %3d%c", percentage, total, magnitude);
        }
    }
}
