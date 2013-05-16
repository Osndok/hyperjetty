package com.allogy.hyperjetty;

import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

import static com.allogy.hyperjetty.ServletProps.NAME;
import static com.allogy.hyperjetty.ServletProps.PATH;
import static com.allogy.hyperjetty.ServletProps.PID;
import static com.allogy.hyperjetty.ServletProps.SERVICE_PORT;
import static com.allogy.hyperjetty.ServletProps.VERSION;

/**
 * User: robert
 * Date: 2013/05/15
 * Time: 4:47 PM
 */
class Filter
{

    Set<String> pid;
    Set<String> port;
    Set<String> path;
    Set<String> name;
    Set<String> version;

    boolean explicitMatchAll;

    Filter orFilter;
    Filter andNotFilter;

    public
    Filter or()
    {
        if (orFilter==null)
        {
            orFilter=new Filter();
        }
        return orFilter;
    }

    public
    Filter andNot()
    {
        if (andNotFilter==null)
        {
            andNotFilter=new Filter();
        }
        return andNotFilter;
    }

    public
    void pid(String pid)
    {
        if (this.pid==null)
        {
            this.pid=new HashSet<String>();
        }
        addToOrList(this.pid, pid);
    }

    public
    void port(String port)
    {
        if (this.port==null)
        {
            this.port=new HashSet<String>();
        }
        addToOrList(this.port, port);
    }

    public
    void path(String path)
    {
        if (this.path==null)
        {
            this.path=new HashSet<String>();
        }
        addToOrList(this.path, path);
    }

    public
    void name(String name)
    {
        if (this.name==null)
        {
            this.name=new HashSet<String>();
        }
        addToOrList(this.name, name);
    }

    public
    void version(String version)
    {
        if (this.version==null)
        {
            this.version=new HashSet<String>();
        }
        addToOrList(this.version, version);
    }

    private void addToOrList(Set<String> set, String s)
    {
        if (s.indexOf(',')>0)
        {
            String[] bits = s.split(",");
            for (String bit : bits) {
                set.add(bit);
            }
        }
        else
        {
            set.add(s);
        }
    }

    /*
    public
    Filter(String port, String path, String name, String version)
    {
        this.port=port;
        this.path=path;
        this.name=name;
        this.version=version;
    }
    */

    public
    boolean matches(Properties p)
    {
        String key;

        if (orFilter!=null && orFilter.matches(p))
        {
            return true;
        }

        if (pid!=null)
        {
            key=PID.toString();
            if (!matches(p, key, pid))
            {
                return false;
            }
        }
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

        if (andNotFilter!=null && andNotFilter.matches(p))
        {
            return false;
        }

        return true;
    }

    private
    boolean matches(Properties p, String key, Set<String> target)
    {
        String value=p.getProperty(key);
        return (value!=null && target.contains(value));
    }

    public
    boolean implicitlyMatchesEverything()
    {
        return (
                pid  == null &&
                port == null &&
                path == null &&
                name == null &&
                version == null &&
                !explicitMatchAll
        );
    }
}
