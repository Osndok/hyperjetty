package com.allogy.infra.hyperjetty.common;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * User: robert
 * Date: 2014/03/26
 * Time: 11:55 PM
 */
public class Config
{
    public static
    String systemPropertyOrEnvironment(String key, String _default)
    {
        String retval=System.getProperty(key);

        if (retval==null)
        {
            retval=System.getenv(key);
        }

        if (retval==null)
        {
            return _default;
        }
        else
        {
            return retval;
        }
    }

    private static final Map<ServletProp,Boolean> viaEnvironment;

    static
    {
        viaEnvironment=new HashMap<ServletProp, Boolean>();
        viaEnvironment.put(ServletProp.DATE_CREATED,   Boolean.TRUE);
        viaEnvironment.put(ServletProp.DATE_STARTED,   Boolean.TRUE);
        viaEnvironment.put(ServletProp.DATE_RESPAWNED, Boolean.TRUE);
        viaEnvironment.put(ServletProp.DEPLOY_DIR,     Boolean.TRUE);
        viaEnvironment.put(ServletProp.JMX_PORT,       Boolean.TRUE);
        viaEnvironment.put(ServletProp.LOG_BASE,       Boolean.TRUE);
        viaEnvironment.put(ServletProp.NAME,           Boolean.TRUE);
        viaEnvironment.put(ServletProp.OPTIONS,        Boolean.TRUE);
        viaEnvironment.put(ServletProp.ORIGINAL_WAR,   Boolean.TRUE);
        viaEnvironment.put(ServletProp.PATH,           Boolean.TRUE);
        viaEnvironment.put(ServletProp.SERVICE_PORT,   Boolean.TRUE);
        viaEnvironment.put(ServletProp.TAGS,           Boolean.TRUE);
        viaEnvironment.put(ServletProp.VERSION,        Boolean.TRUE);
        viaEnvironment.put(ServletProp.WITHOUT,        Boolean.TRUE);
    }

    public static
    boolean isAvailableViaEnvironment(ServletProp key)
    {
        return viaEnvironment.containsKey(key);
    }

    public static
    Set<ServletProp> getPropsAvailableViaEnvironment()
    {
        return viaEnvironment.keySet();
    }

}
