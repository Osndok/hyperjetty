package com.allogy.infra.hyperjetty.server;

import com.allogy.infra.hyperjetty.common.ServletProp;

import java.util.*;

import static com.allogy.infra.hyperjetty.common.ServletProp.HEAP_SIZE;
import static com.allogy.infra.hyperjetty.common.ServletProp.JMX_PORT;
import static com.allogy.infra.hyperjetty.common.ServletProp.NAME;
import static com.allogy.infra.hyperjetty.common.ServletProp.PATH;
import static com.allogy.infra.hyperjetty.common.ServletProp.PERM_SIZE;
import static com.allogy.infra.hyperjetty.common.ServletProp.PID;
import static com.allogy.infra.hyperjetty.common.ServletProp.SERVICE_PORT;
import static com.allogy.infra.hyperjetty.common.ServletProp.TAGS;
import static com.allogy.infra.hyperjetty.common.ServletProp.VERSION;

/**
 * User: robert
 * Date: 2013/05/15
 * Time: 4:47 PM
 */
class Filter
{

    //Safe, no restart required
    Set<String> name;
    Set<String> tag;
    Set<String> version;

    //Unsafe, requires restart
    Set<String> port;
    Set<String> path;
    Set<String> jmxPort;
    Set<String> heap;
    Set<String> perm;

    //Unsettable, but can be used as a key
    Set<String> pid;

    //Late comers, does not actually filter (shouldn't we have relative filters like --stack<5m ???)
    Set<String> war;
    Set<String> stack;

    public
    void applySetOperationTo(Properties properties)
    {
        if (name!=null) setPropertyKeyToOnlySetEntry(properties, NAME, name);
        if (tag !=null) setPropertyKeyToMultipleEntry(properties, TAGS, tag);
        if (path!=null) setPropertyKeyToOnlySetEntry(properties, PATH, path);
        if (heap!=null) setPropertyKeyToOnlySetEntry(properties, HEAP_SIZE, heap);
        if (perm!=null) setPropertyKeyToOnlySetEntry(properties, PERM_SIZE, perm);
        if (port!=null) setPropertyKeyToOnlySetEntry(properties, SERVICE_PORT, port);

        if (jmxPort!=null) setPropertyKeyToOnlySetEntry(properties, JMX_PORT, jmxPort);
        if (version!=null) setPropertyKeyToOnlySetEntry(properties, VERSION , version);

        if (pid!=null)
        {
            throw new UnsupportedOperationException("cannot set pid (it is automatically set on launch)");
        }

        if (war!=null)
        {
            throw new UnsupportedOperationException("unimplemented: war file cannot be changed at the moment");
        }
    }

    private
    void setPropertyKeyToMultipleEntry(Properties properties, ServletProp key, Set<String> set)
    {
        StringBuilder csv=null;
        for (String s : set) {
            if (csv==null)
            {
                csv=new StringBuilder();
            }
            else
            {
                csv.append(',');
            }
            csv.append(s);
        }

        properties.setProperty(key.toString(), csv.toString());
    }

    private
    void setPropertyKeyToOnlySetEntry(Properties properties, ServletProp key, Set<String> set)
    {
        Iterator<String> i = set.iterator();
        if (!i.hasNext())
        {
            throw new IllegalArgumentException("expecting exactly one "+key+", but found zero");
        }
        String value=i.next();
        if (i.hasNext())
        {
            throw new IllegalArgumentException("expecting exactly one "+key+", but found "+set.size());
        }
        if (value==null || value.length()==0 || value.equals("null"))
        {
            properties.remove(key.toString());
        }
        else
        {
            properties.setProperty(key.toString(), value);
        }
    }

    private
    boolean allMatchCriteriaAreNull()
    {
        return  pid  == null &&
                heap == null &&
                tag  == null &&
                perm == null &&
                port == null &&
                path == null &&
                name == null &&
                war  == null &&
                version == null &&
                jmxPort == null
                ;
    }

    /*
     * What features (if this is a "set" command) would require that the effected servlet(s) be restarted ?
     */
    public
    boolean setRequiresServletRestart()
    {
        //!!!: would probably need "war" too...
        return  port != null ||
                path != null ||
                heap != null ||
                perm != null ||
                jmxPort !=null
                ;
    }


    HashMap<String, String> options;

    boolean explicitMatchAll;

    Filter orFilter;
    Filter andNotFilter;

    Filter whereFilter;
    Filter kludgeSetFilter;

    /*
    @Override
    public
    String toString()
    {
        StringBuilder sb=new StringBuilder();

    }
    */

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
    Filter where()
    {
        if (whereFilter==null)
        {
            whereFilter=new Filter();
        }
        return whereFilter;
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
    void heap(String heap)
    {
        if (this.heap == null)
        {
            this.heap=new HashSet<String>();
        }
        addToOrList(this.heap, heap);
    }

    public
    void jmx(String jmxPort)
    {
        mustLookLikeANumber(jmxPort);

        if (this.jmxPort == null)
        {
            this.jmxPort=new HashSet<String>();
        }

        addToOrList(this.jmxPort, jmxPort);
    }

    public
    void pid(String pid)
    {
        mustLookLikeANumber(pid);

        if (this.pid==null)
        {
            this.pid=new HashSet<String>();
        }

        addToOrList(this.pid, pid);
    }

    public
    void perm(String perm)
    {
        if (this.perm==null)
        {
            this.perm=new HashSet<String>();
        }
        addToOrList(this.perm, perm);
    }

    public
    void port(String port)
    {
        mustLookLikeANumber(port);

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
    void stack(String stack)
    {
        if (this.stack==null)
        {
            this.stack=new HashSet<String>();
        }
        addToOrList(this.stack, stack);
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

    public
    void war(String war)
    {
        if (this.war==null)
        {
            this.war=new HashSet<String>();
        }
        addToOrList(this.war, war);
    }

    public
    void tag(String tag)
    {
        if (this.tag == null)
        {
            this.tag = new HashSet<String>();
        }
        addToOrList(this.tag, tag);
    }

    public
    void option(String option, String value)
    {
        if (this.options==null)
        {
            this.options=new HashMap<String,String>();
        }
        this.options.put(option, value);
    }

    public
    String getOption(String option, String _default)
    {
        if (this.options==null || !this.options.containsKey(option))
        {
            return _default;
        }
        return this.options.get(option);
    }

    Set<String> state;

    public
    void state(String state)
    {
        state=validateServletState(state);

        if (this.state==null)
        {
            this.state=new HashSet<String>();
        }

        addToOrList(this.state, state);
    }

    /**
     * A little softer than: ServletState.valueOf(state)
     * @param state
     * @return
     */
    private
    String validateServletState(String state)
    {
        String l=state.toLowerCase();
        if (l.contains("live")) return ServletState.LIVE.toString(); //e.g. "alive"
        if (l.contains("dead")) return ServletState.DEAD.toString();
        if (l.contains("stop")) return ServletState.STOP.toString(); //e.g. "stopped"

        throw new IllegalArgumentException("Unknown servlet state: "+state);
    }

    Set <String> without;

    public
    void without(String option)
    {
        if (this.without==null)
        {
            this.without=new HashSet<String>();
        }
        addToOrList(this.without, option);
    }

    private
    void addToOrList(Set<String> set, String s)
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
    boolean matches(Properties p, ServletStateChecker stateChecker)
    {
        //Since getting the state of a servlet is considered expensive, we should try everything else first...

        if (orFilter==null)
        {
            if (!localNonStateMatches(p)) return false;
        }
        else
        if (orFilter.matches(p, stateChecker))
        {
            return true;
        }
        else
        if (!localNonStateMatches(p) /* && orFilter !=null */)
        {
            return false;
        }

        if (andNotFilter!=null && andNotFilter.matches(p, stateChecker))
        {
            return false;
        }

        if (state!=null)
        {
            System.err.println("filter must check servlet state");
            ServletState servletState=stateChecker.getServletState(p);
            return state.contains(servletState.toString());
        }

        return true;
    }

    private
    boolean localNonStateMatches(Properties p)
    {
        String key;

        if (heap!=null)
        {
            key=HEAP_SIZE.toString();
            if (!matches(p, key, heap))
            {
                return false;
            }
        }
        if (jmxPort!=null)
        {
            key=JMX_PORT.toString();
            if (!matches(p, key, jmxPort))
            {
                return false;
            }
        }
        if (pid!=null)
        {
            key=PID.toString();
            if (!matches(p, key, pid))
            {
                return false;
            }
        }
        if (perm!=null)
        {
            key=PERM_SIZE.toString();
            if (!matches(p, key, perm))
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
        if (tag != null)
        {
            key= TAGS.toString();
            if (!multiMatches(p, key, tag))
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

    /* SLOW! */
    private
    boolean multiMatches(Properties p, String key, Set<String> target)
    {
        String value=p.getProperty(key);

        if (value==null)
        {
            return false;
        }
        else if (value.indexOf(',')>=0)
        {
            for (String s : value.split(",")) {
                if (target.contains(s))
                {
                    return true;
                }
            }
            return false;
        }
        else
        {
            return (target.contains(value));
        }
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
                allMatchCriteriaAreNull() &&
                andNotFilter == null &&
                !explicitMatchAll
        );
    }

    public boolean setCanOnlyBeAppliedToOnlyOneServlet()
    {
        return (port != null || jmxPort != null);
    }

    private
    void mustLookLikeANumber(String s)
    {
        Integer.parseInt(s);
    }
}
