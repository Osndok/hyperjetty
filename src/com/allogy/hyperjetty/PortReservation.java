package com.allogy.hyperjetty;

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
}