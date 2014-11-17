package com.allogy.infra.hyperjetty.server;

import java.io.IOException;
import java.net.ServerSocket;

/**
 * User: robert
 * Date: 2013/05/13
 * Time: 4:03 PM
 */
class PortReservation
{
    private final int servicePort;
    private final int jmxPort;

    private final ServerSocket serviceSocket;
    private final ServerSocket jmxSocket;

    PortReservation(int servicePort, int jmxPort) throws IOException
    {
        this.servicePort = servicePort;
        this.jmxPort = jmxPort;

        this.serviceSocket=new ServerSocket(servicePort);

        boolean gotJmx=false;
        try {
            this.jmxSocket=new ServerSocket(jmxPort);
            gotJmx=true;
        } finally {
            if (!gotJmx)
            {
                serviceSocket.close();
            }
        }
    }

    public
    void release() throws IOException
    {
        serviceSocket.close();
        jmxSocket.close();
    }

    public
    int getServicePort()
    {
        return servicePort;
    }

    public
    int getJmxPort()
    {
        return jmxPort;
    }

    @Override
    public
    String toString()
    {
        return "PortReservation:"+servicePort+":"+jmxPort;
    }

    public static int LAST_SUCCESSFUL_RESERVATION=-1;

    public static
    PortReservation startingAt(int servicePort, int jmxPort)
    {
        int MAX=Math.max(servicePort,jmxPort)-Math.min(servicePort, jmxPort);

        for (int i=LAST_SUCCESSFUL_RESERVATION+1; i<MAX; i++)
        {
            try {
                PortReservation retval=new PortReservation(servicePort+i, jmxPort+i);
                LAST_SUCCESSFUL_RESERVATION=i;
                return retval;
            } catch (IOException e) {
                //expected...
            }
        }

        if (LAST_SUCCESSFUL_RESERVATION>=0)
        {
            System.err.println("Looping back around to: "+servicePort+" / "+jmxPort);

            for (int i=0; i<=LAST_SUCCESSFUL_RESERVATION; i++)
            {
                try {
                    PortReservation retval=new PortReservation(servicePort+i, jmxPort+i);
                    LAST_SUCCESSFUL_RESERVATION=i;
                    return retval;
                } catch (IOException e) {
                    //expected...
                }
            }
        }

        throw new IllegalStateException("tried all "+MAX+" available ports, could not locate valid pair");
    }

    public static
    PortReservation exactly(String servicePort, String jmxPort)
    {
        try {
            return new PortReservation(Integer.parseInt(servicePort), Integer.parseInt(jmxPort));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static
    PortReservation givenFixedServicePort(String servicePort, int minimumServicePort, int minimumJMXPort)
    {
        int offset=minimumJMXPort-minimumServicePort;
        int port=Integer.parseInt(servicePort);
        int jmxPort=port+offset;

        try {
            return new PortReservation(port, jmxPort);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static
    PortReservation givenFixedJMXPort(String s_jmxPort, int minimumServicePort, int minimumJMXPort)
    {
        int offset=minimumJMXPort-minimumServicePort;
        int jmxPort=Integer.parseInt(s_jmxPort);//port+offset;
        int port=jmxPort-offset;

        try {
            return new PortReservation(port, jmxPort);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

	/**
	 * Much like the startingAt() port allocation scheme, except that this one attempts to smear the names across
	 * the given address space, clumping related application names together.
	 */
	public static
	PortReservation nameCentric(String appName, int servicePort, int jmxPort)
	{
		int MAX=Math.max(servicePort,jmxPort)-Math.min(servicePort, jmxPort);
		int startingPoint=nameHash(appName, MAX);

		if (startingPoint<0 || startingPoint>=MAX)
		{
			startingPoint=0;
		}

		for (int i=startingPoint; i<MAX; i++)
		{
			try
			{
				PortReservation retval=new PortReservation(servicePort+i, jmxPort+i);
				return retval;
			}
			catch (IOException e)
			{
				//expected...
			}
		}

		for (int i=0; i<startingPoint; i++)
		{
			try
			{
				PortReservation retval=new PortReservation(servicePort+i, jmxPort+i);
				return retval;
			}
			catch (IOException e)
			{
				//expected...
			}
		}

		throw new IllegalStateException("tried all "+MAX+" available ports, could not locate valid pair");
	}

	/**
	 * Given an application name, return a semi-consistent hash of that name that will clump like names together
	 * but generally smear disparate names across the 0-to-max spectrum in a semi-alphabetical ordering ('a'->0,
	 * 'z'->max).
	 *
	 * @param appName
	 * @param max
	 * @return
	 */
	public static
	int nameHash(String appName, int max)
	{
		int l=appName.length();

		if (l==0) return 0;

		appName=appName.toLowerCase();

		char first=appName.charAt(0);
		char last=appName.charAt(l-1);
		char middle=appName.charAt(l/2);

		double scale=(first-'a')/26d + (last-'a')/(26d*26) + (middle-'a')/(26d*26*26);

		return (int)(max*scale);
	}
}
