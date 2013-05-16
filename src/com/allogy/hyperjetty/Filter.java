package com.allogy.hyperjetty;

import java.util.Properties;

import static com.allogy.hyperjetty.ServletProps.*;

/**
 * User: robert
 * Date: 2013/05/15
 * Time: 4:47 PM
 */
class Filter
{

    final String port;
    final String path;
    final String name;
    final String version;

    public boolean explicitMatchAll;

    public
    Filter(String port, String path, String name, String version)
    {
        this.port=port;
        this.path=path;
        this.name=name;
        this.version=version;
    }

    public
    boolean matches(Properties p)
    {
        String key;

        if (port!=null)
        {
            key=SERVICE_PORT.toString();
            if (!matches(p, key, port))
            {
                return false;
            }
        }
        if (path!=null)
        {
            key=PATH.toString();
            if (!matches(p, key, path))
            {
                return false;
            }
        }
        if (name!=null)
        {
            key=NAME.toString();
            if (!matches(p, key, name))
            {
                return false;
            }
        }
        if (version!=null)
        {
            key=VERSION.toString();
            if (!matches(p, key, version))
            {
                return false;
            }
        }
        return true;
    }

    private
    boolean matches(Properties p, String key, String target)
    {
        String value=p.getProperty(key);
        return (value!=null && target.equals(value));
    }

    public
    boolean implicitlyMatchesEverything()
    {
        return (
                port == null &&
                path == null &&
                name == null &&
                version == null &&
                !explicitMatchAll
        );
    }
}
