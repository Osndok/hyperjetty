package com.allogy.hyperjetty;

import java.io.*;
import java.util.*;

/**
 * User: robert
 * Date: 2013/05/28
 * Time: 3:11 PM
 */
public class LaunchOptions
{
    private final File optionsDirectory;

    public LaunchOptions(File libDirectory)
    {
        this.optionsDirectory=new File(libDirectory, "options");
    }

    private Set<String> jarFiles=new HashSet<String>();

    public void addJar(File jar)
    {
        jarFiles.add(jar.getAbsolutePath());
    }

    public void appendClassPath(StringBuilder sb)
    {
        Iterator<String> i=jarFiles.iterator();
        sb.append(i.next());
        while (i.hasNext())
        {
            sb.append(':');
            sb.append(i.next());
        }
    }

    Set<String> jettyConfigFiles=new HashSet<String>();

    public void addJettyConfig(File xml)
    {
        jettyConfigFiles.add(xml.getAbsolutePath());
    }

    /**
     * Given an option's common-name (like "shared-sessions"), locate it's required config files
     * and jar files.
     */
    public void enable(String optionName) throws IOException
    {
        PrintStream log=System.err;
        File optionDescriptionFile=new File(optionsDirectory, optionName+".config");

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
                            addJavaDefine(line.substring(line.indexOf("_")+1));
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

    List<String> javaDefines=new ArrayList<String>();

    public
    void addJavaDefine(String s)
    {
        System.err.println("Got java define: -D"+s);
        javaDefines.add(s);
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

    private File possiblyRelativePath(String value)
    {
        if (value.startsWith("/") || value.startsWith("."))
        {
            return new File(value);
        }
        else
        {
            return new File(optionsDirectory, value);
        }
    }
}
