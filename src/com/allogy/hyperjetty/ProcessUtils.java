package com.allogy.hyperjetty;

import java.io.*;

/**
 * Minimal logic to check on a process-state on Linux & OSX computers.
 *
 * User: robert
 * Date: 2013/08/30
 * Time: 3:30 PM
 */
public abstract class ProcessUtils
{
    /**
     * If true, then running on a system where /proc/$PID has useful information, otherwise
     * running on a OS-X development machine.
     */
    private static boolean LINUX=true;

    static
    {
        LINUX=!new File("/System/Library").exists();
    }

    private static PrintStream log=System.err;

    /**
     * @param pid
     * @return true if & only if the process is expected to be running, and is not certifiably dead.
     */
    static
    boolean isRunning(int pid)
    {
        Boolean state=processState(pid);

        if (state==null)
        {
            log.println("cannot determine process state: pid="+pid);
            return true; //cannot confirm it is dead.
        }

        return state;
    }

    static
    Boolean processState(int pid)
    {
        if (pid<0)
        {
            ///!!!: bug: setting the pid<=1 should indicate that the servlet should *NOT* be restarted automatically
            log.println("special pid indicates last state was not running / stopped: "+pid);
            return Boolean.FALSE;
        }

        if (LINUX)
        {
            File file=new File("/proc/"+pid+"/cmdline");
            if (file.exists())
            {
                log.println("found: "+file);
                try {
                    String contents=FileUtils.contentsAsString(file);
                    if (contents==null || contents.contains("java"))
                    {
                        log.println("pid "+pid+" is present and has java marker: "+file);
                        return Boolean.TRUE;
                    }
                    else
                    {
                        log.println("pid "+pid+" is present, but has no java marker: "+file);
                        return Boolean.FALSE;
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    return null; //cannot verify the command name, but matching PID is sufficient, I guess...
                }
            }
            else
            {
                log.println("dne: "+file);
                return Boolean.FALSE;
            }
        }
        else
        {
            try {
                Process process = Runtime.getRuntime().exec(new String[]{
                        "ps",
                        "-p",
                        Integer.toString(pid)
                });

                StringBuilder sb=new StringBuilder();
                InputStream inputStream=process.getInputStream();

                int c;
                while ((c=inputStream.read())>0)
                {
                    sb.append((char)c);
                }

                int status=process.waitFor();
                if (status==0)
                {
                    if (sb.indexOf("java")>0)
                    {
                        log.println("pid "+pid+" is present, with java marker");
                        return Boolean.TRUE;
                    }
                    else
                    {
                        log.println("pid "+pid+" is present, but lacks java marker");
                        return Boolean.FALSE;
                    }
                }
                else
                {
                    log.println("pid "+pid+" is not present");
                    return Boolean.FALSE;
                }
            } catch (IOException e) {
                e.printStackTrace();
                return null;
            } catch (InterruptedException e) {
                e.printStackTrace();
                return null;
            }
        }
    }

}
