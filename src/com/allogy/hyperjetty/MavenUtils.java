package com.allogy.hyperjetty;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * User: robert
 * Date: 2013/09/24
 * Time: 11:55 AM
 */
public class MavenUtils
{
    public static
    String readVersionNumberFromWarFile(File warFile) throws IOException
    {
        System.err.println("readVersionNumberFromWarFile("+warFile+")");
        JarFile jar=new JarFile(warFile);
        try {
            jar.entries();
            for (Enumeration e = jar.entries() ; e.hasMoreElements() ;)
            {
                JarEntry entry = (JarEntry) e.nextElement();
                //System.err.println("entry: "+entry.getName());
                //System.err.println(o.getClass() + " / " + o);
                String name=entry.getName();
                if (name.endsWith("/pom.properties"))
                {
                    InputStream inputStream=jar.getInputStream(entry);
                    try {
                        Properties p=new Properties();
                        p.load(inputStream);
                        return p.getProperty("version");
                    } finally {
                        inputStream.close();
                    }
                }
            }
        } finally {
            jar.close();
        }
        return null;
    }

    public static
    void main(String[] args)
    {
        for (String s : args) {
            System.out.print(s + "\t");
            try {
                System.out.print(readVersionNumberFromWarFile(new File(s)));
            } catch (IOException e) {
                e.printStackTrace();
            }
            System.err.println();
        }
    }
}
