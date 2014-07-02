package com.allogy.infra.hyperjetty.server;

import java.io.*;
import java.util.*;

/**
 * User: robert
 * Date: 2013/05/28
 * Time: 3:11 PM
 */
public class LaunchOptions
{
    private final File optionsDirectory1;
    private final File optionsDirectory2;

    public
    LaunchOptions(File primaryDirectory, File secondaryDirectory)
    {
        this.optionsDirectory1=new File(primaryDirectory, "options");
        this.optionsDirectory2=new File(secondaryDirectory, "options");
    }

    private Set<String> jarFiles=new LinkedHashSet<String>();

    public
    void addJar(File jar)
    {
        jarFiles.add(jar.getAbsolutePath());
    }

    public
    void addJarIfReadable(File jar)
    {
        if (jar.canRead())
        {
            jarFiles.add(jar.getAbsolutePath());
        }
    }

    public
    void appendClassPath(StringBuilder sb)
    {
        Iterator<String> i=jarFiles.iterator();
        sb.append(i.next());
        while (i.hasNext())
        {
            sb.append(':');
            sb.append(i.next());
        }
    }

    private final Set<String> blacklistedOptionNames=new HashSet<String>();

    private final Set <String> jettyConfigFiles=new HashSet<String>();

    public
    Set<String> getJettyConfigFiles()
    {
        return jettyConfigFiles;
    }

    public
    void addJettyConfig(File xml)
    {
        jettyConfigFiles.add(xml.getAbsolutePath());
    }

    public
    void blacklist(String optionName)
    {
        blacklistedOptionNames.add(optionName);
    }

    public
    boolean isBlacklisted(String optionName)
    {
        return blacklistedOptionNames.contains(optionName);
    }

    /**
     * Given an option's common-name (like "shared-sessions"), locate it's required config files
     * and jar files.
     */
    public
    void enable(String optionName) throws IOException
    {
        PrintStream log=System.err;
        File optionDescriptionFile=new File(optionsDirectory1, optionName+".config");

        if (!optionDescriptionFile.exists())
        {
            optionDescriptionFile=new File(optionsDirectory2, optionName+".config");
            log.println("falling back to: "+optionDescriptionFile+" (should now be in: '"+optionsDirectory1+"')");
        }

        if (blacklistedOptionNames.contains(optionName))
        {
            log.println("blacklisted: "+optionDescriptionFile);
            return;
        }

        if (optionDescriptionFile.exists())
        {
            BufferedReader br=new BufferedReader(new InputStreamReader(new FileInputStream(optionDescriptionFile)));

            try {
                String line;

                while ((line=br.readLine())!=null)
                {
                    int percentSign=line.indexOf('#');

                    if (percentSign==0)
                    {
                        //just a comment...
                        continue;
                    }
                    else if (percentSign>0)
                    {
                        log.println("reducing: "+line);
                        line=line.substring(0, percentSign);
                        log.println(" .... to: "+line);
                    }

                    line=line.trim();

                    if (line.length()==0)
                    {
                        continue; //just a line of white space
                    }

                    int equalsSign=line.indexOf('=');

                    if (equalsSign<=0)
                    {
                        if (line.toLowerCase().startsWith("java_"))
                        {
                            addJavaDefine(stripUnderscoredPrefix(line));
                        }
                        else
                        {
                            log.println("WARNING: malformed line: "+line);
                        }
                    }
                    else
                    {
                        String keyUpper=line.substring(0, equalsSign).trim();
                        String key=keyUpper.toLowerCase();
                        String value=line.substring(equalsSign+1).trim();
                        log.println(optionName+" "+key+" -> '"+value+"'");

                        if (key.startsWith("warn"))
                        {
                            log.println("WARNING: "+value);
                        }
                        else if (key.equals("jetty_config"))
                        {
                            addJettyConfig(possiblyRelativePath(value));
                        }
                        else if (key.equals("jar"))
                        {
                            addJar(possiblyRelativePath(value));
                        }
                        else if (key.equals("include"))
                        {
                            log.println("INCLUDE: "+value);
                            enable(value);
                        }
                        else if (key.startsWith("java_"))
                        {
                            addJavaDefine(line.substring(line.indexOf("_") + 1));
                        }
                        else
                        {
                            log.println("WARNING: unrecognized option '"+key+"' in "+optionName+" config: "+optionDescriptionFile);
                        }
                    }
                }
            } finally {
                br.close();
            }
        }
        else
        {
            log.println("WARNING: option configuration does not exist: "+optionDescriptionFile);
        }
    }

    static
    String stripUnderscoredPrefix(String line)
    {
        return line.substring(line.indexOf("_")+1);
    }

    List<String> javaDefines=new ArrayList<String>();

    public
    void addJavaDefine(String s)
    {
        if (s.indexOf(' ')>=0)
        {
            System.err.println("unable to set java defines whose keys (or values) contain spaces: "+s);
        }
        else
        {
            System.err.println("Got java define: -D"+s);
            javaDefines.add(s);
        }
    }

    public
    void addJavaDefine(String key, String value)
    {
        if (key.indexOf(' ')>=0)
        {
            System.err.println("unable to set java defines whose keys (or values) contain spaces: "+key+" --/--> "+value);
        }
        else
        if (value.indexOf(' ')>=0)
        {
            System.err.println("unable to set java defines whose values (or keys) contain spaces: "+key+" --/--> "+value);
        }
        else
        {
            javaDefines.add(key+"="+value);
        }
    }

    public
    boolean hasJavaDefine(String s)
    {
        for (String s1 : javaDefines) {
            if (s.equals(s1)) return true;
        }
        return false;
    }

    public
    String getJavaDefines()
    {
        StringBuilder sb=new StringBuilder();
        for (String s : javaDefines) {
            sb.append(" -D").append(s);
        }
        return sb.toString();
    }

    private
    File possiblyRelativePath(String value)
    {
        if (value.startsWith("/") || value.startsWith("."))
        {
            return new File(value);
        }
        else
        {
            File retval=new File(optionsDirectory1, value);

            if (!retval.exists())
            {
                final PrintStream log=System.err;

                retval=new File(optionsDirectory2, value);
                log.println("falling back to: "+retval+" (should now be in: '"+optionsDirectory1+"')");
            }

            return retval;
        }
    }

}
