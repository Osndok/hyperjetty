package com.allogy.infra.hyperjetty.webapp;

import com.allogy.infra.hyperjetty.common.Config;
import com.allogy.infra.hyperjetty.common.ServletProp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/*
import com.sun.jna.Library;
import com.sun.jna.Native;
*/

/**
 * A convenience class for webapps that are operating under the control of hyperjetty. Should be safe
 * to use outside of hyperjetty too.
 *
 * User: robert
 * Date: 2014/03/27
 * Time: 11:30 AM
 */
public class Hyperjetty
{
    private static final Logger log=LoggerFactory.getLogger(Hyperjetty.class);

    public static
    String get(ServletProp key)
    {
        if (Config.isAvailableViaEnvironment(key))
        {
            return System.getenv("HJ_"+key.toString());
        }
        else
        {
            if (properties==null || propertiesIsStale())
            {
                synchronized (Hyperjetty.class)
                {
                    if (properties==null || propertiesIsStale())
                    {
                        properties=readProperties();
                        propertiesReadTime=System.currentTimeMillis();
                    }
                }
            }
            return properties.getProperty(key.toString());
        }
    }

    private static
    Properties readProperties()
    {
        File file=getConfigFile();

        if (file==null) return new Properties();

        FileInputStream fis=null;
        try
        {
            fis=new FileInputStream(file);
            Properties retval=new Properties();
            retval.load(fis);
            return retval;
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
        finally
        {
            try
            {
                fis.close();
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        }
    }

    //    env.put("HJ_PORT"      , Integer.toString(servicePort));
    //    env.put("HJ_STATS"     , (hasStatsServlet?"TRUE":"FALSE"));
    //...

    //    env.put("HJ_LOG"       , logFile);
    public static
    File getOutputLog()
    {
        String fileName=System.getenv("HJ_LOG");

        if (fileName==null)
        {
            return null;
        }
        else
        {
            return new File(fileName);
        }
    }

    //    env.put("HJ_ACCESS_LOG", accessLog);
    public static
    File getAccessLog()
    {
        String fileName=System.getenv("HJ_ACCESS_LOG");

        if (fileName==null)
        {
            return null;
        }
        else
        {
            return new File(fileName);
        }
    }

    //    env.put("HJ_CONFIG_FILE", configFile.getAbsolutePath());
    public static
    File getConfigFile()
    {
        String fileName=System.getenv("HJ_CONFIG_FILE");

        if (fileName==null)
        {
            return null;
        }
        else
        {
            return new File(fileName);
        }
    }

    private static
    boolean propertiesIsStale()
    {
        return (System.currentTimeMillis() > propertiesReadTime+PROPERTIES_TIMEOUT);
    }

    private static final long PROPERTIES_TIMEOUT = TimeUnit.MINUTES.toMillis(5);

    private static volatile Properties properties;
    private static volatile long propertiesReadTime;

    private static Integer processId;

    public static
    Integer getProcessID()
    {
        if (processId==null)
        {
            synchronized (Hyperjetty.class)
            {
                if (processId==null)
                {
                    try {
                        processId=getProcessIDUsingPrivateInterfaces();
                    } catch (Throwable t) {
                        log.error("private interface method failed", t);
                    }
                }

                if (processId==null)
                {
                    throw new RuntimeException("cannot get process id from private java interfaces");

                    /*
                    try {
                        processId=getProcessIDUsingJNA();
                    } catch (Throwable t) {
                        log.error("jna access failed", t);
                    }
                    */
                }
            }
        }
        return processId;
    }

    private static
    Integer getProcessIDUsingPrivateInterfaces() throws InvocationTargetException, IllegalAccessException,
            NoSuchMethodException, NoSuchFieldException
    {
        java.lang.management.RuntimeMXBean runtime = java.lang.management.ManagementFactory.getRuntimeMXBean();
        java.lang.reflect.Field jvm = runtime.getClass().getDeclaredField("jvm");
        jvm.setAccessible(true);
        sun.management.VMManagement mgmt = (sun.management.VMManagement) jvm.get(runtime);
        java.lang.reflect.Method pid_method = mgmt.getClass().getDeclaredMethod("getProcessId");
        pid_method.setAccessible(true);
        return (Integer) pid_method.invoke(mgmt);
    }

    /*
    private interface CLibrary extends Library
    {
        int getpid ();
    }

    public Integer getProcessIDUsingJNA()
    {
        CLibrary INSTANCE = (CLibrary) Native.loadLibrary("c", CLibrary.class);
        return INSTANCE.getpid();
    }
    */

}
