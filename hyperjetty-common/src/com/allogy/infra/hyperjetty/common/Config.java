package com.allogy.infra.hyperjetty.common;

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
}
