package com.allogy.hyperjetty;

import javax.management.*;
import javax.management.openmbean.CompositeData;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import java.io.IOException;
import java.net.MalformedURLException;
import java.rmi.UnmarshalException;
import java.util.Set;

/**
 *
 * Based on:
 * http://tapestryjava.blogspot.com/2010/11/starting-and-stopping-jetty-gracefully.html
 *
 * User: robert
 * Date: 2013/05/14
 * Time: 10:53 PM
 */
class JMXUtils
{

    public static void printMemoryUsageGivenJMXPort(int jmxPort) throws IOException, MalformedObjectNameException,
            MBeanException, InstanceNotFoundException, ReflectionException, AttributeNotFoundException
    {
        JMXServiceURL jmxServiceURL=serviceUrlForPort(jmxPort);

        JMXConnector jmxConnector = JMXConnectorFactory.connect(jmxServiceURL);

        try {

            MBeanServerConnection mBeanServerConnection = jmxConnector.getMBeanServerConnection();

            ObjectName objectName=new ObjectName("java.lang:type=Memory");

            CompositeData compositeData = (CompositeData) mBeanServerConnection.getAttribute(objectName, "HeapMemoryUsage");

            /*
            System.err.println("-----Heap-----");

            for (String key : compositeData.getCompositeType().keySet()) {
                Object o = compositeData.get(key);
                System.err.println(key+"\t= "+o+" "+o.getClass());
            }
            */

            Long heapMax=(Long)compositeData.get("max");
            Long heapUsed=(Long)compositeData.get("used");

            long heapPercentage=100*heapUsed/heapMax;

            System.err.println("Heap Percentage Used: "+heapPercentage+"%");

            objectName=new ObjectName("java.lang:type=MemoryPool,name=CMS Perm Gen");

            compositeData=(CompositeData)mBeanServerConnection.getAttribute(objectName, "Usage");

            /*
            System.err.println("-----Permgen-----");

            for (String key : compositeData.getCompositeType().keySet()) {
                Object o = compositeData.get(key);
                System.err.println(key+"\t= "+o+" "+o.getClass());
            }
            */

            Long permMax=(Long)compositeData.get("max");
            Long permUsed=(Long)compositeData.get("used");

            long permPercentage=100*permUsed/permMax;

            System.err.println("Perm Percentage Used: "+permPercentage+"%");

        } finally {
            jmxConnector.close();
        }
    }

    private static boolean INTERPRET_FIRST_STOP_COMMAND_AS_JMX_DEBUG_DUMP =false;

    public static void tellJettyContainerToStopAtJMXPort(int jmxPort) throws IOException, MalformedObjectNameException,
            MBeanException, InstanceNotFoundException, ReflectionException, IntrospectionException,
            AttributeNotFoundException
    {
        JMXServiceURL jmxServiceURL=serviceUrlForPort(jmxPort);

        JMXConnector jmxConnector = JMXConnectorFactory.connect(jmxServiceURL);

        try {

            MBeanServerConnection mBeanServerConnection = jmxConnector.getMBeanServerConnection();

            if (INTERPRET_FIRST_STOP_COMMAND_AS_JMX_DEBUG_DUMP)
            {
                INTERPRET_FIRST_STOP_COMMAND_AS_JMX_DEBUG_DUMP =false;
                debugJettyMBeans(mBeanServerConnection);
                throw new RuntimeException("testing");
            }

            ObjectName objectName=new ObjectName("org.eclipse.jetty.server:type=server,id=0");

            try {
                mBeanServerConnection.invoke(objectName, "stop", null, null);
            } catch (UnmarshalException e) {
                //Yep... jetty can react even before the "okay" is wrapped up and sent back... now that's fast!
                if (e.getCause()!=null) {
                    System.err.println("benign: "+e.getCause()); //should be EOFException
                } else {
                    e.printStackTrace();
                    System.err.println("warn: "+e);
                }
            }

        } finally {
            /*
            Strangely enough, jetty reacts so fast that this 'close' command tries to re-open the connection ?!?!?
            But if we just don't call it, then we'll get ugly message in the server logs... uggh...
            */
            try {
                jmxConnector.close();
            } catch (Throwable t) {
                System.err.println("benign: "+t.toString()+" (faulty reconnect attempt while stopping)");
            }

        }
    }

    private static void debugJettyMBeans(MBeanServerConnection mBeanServerConnection) throws IOException,
            IntrospectionException, InstanceNotFoundException, ReflectionException, AttributeNotFoundException,
            MBeanException
    {
        System.err.println("\n\nDump of JMX MBeans that should be available from Jetty\n--------------------------");
        Set<ObjectName> objectNames = mBeanServerConnection.queryNames(null, null);
        for (ObjectName objectName : objectNames) {
            System.err.println(objectName.toString());
        }
        System.err.println();

        for (ObjectName objectName : objectNames)
        {

            if (objectName.toString().startsWith("org.")) {
                continue;
            }
            /* /
            if (!objectName.toString().startsWith("JMI")) {
                continue;
            }
            */
            System.err.println();
            System.err.println(objectName.toString());
            System.err.println("-------------------------------------------");
            System.err.println();
            /*
            for (Map.Entry<String, String> me : objectName.getKeyPropertyList().entrySet()) {
                System.err.println(me.getKey()+"\t= "+me.getValue());
            }
            *
            for (ObjectInstance objectInstance : mBeanServerConnection.queryMBeans(objectName, null)) {
                System.err.println(objectInstance);
            }
            */
            MBeanInfo mBeanInfo = mBeanServerConnection.getMBeanInfo(objectName);

            for (MBeanAttributeInfo mBeanAttributeInfo : mBeanInfo.getAttributes())
            {
                String name=mBeanAttributeInfo.getName();
                Object value=null;
                try {
                    value=mBeanServerConnection.getAttribute(objectName, name);
                } catch (Throwable t) {
                    value="-Unknown-";
                }
                System.err.println(name+"\n\tvalue = '"+value+"'\n\tdesc = "+mBeanAttributeInfo.getDescription());
            }

        }

    }

    private static JMXServiceURL serviceUrlForPort(int jmxPort) throws MalformedURLException
    {
        return new JMXServiceURL("service:jmx:rmi:///jndi/rmi://localhost:"+jmxPort+"/jmxrmi");
    }
}