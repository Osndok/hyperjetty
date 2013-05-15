package com.allogy.hyperjetty;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

import static com.allogy.hyperjetty.ServletProps.HEAP_SIZE;
import static com.allogy.hyperjetty.ServletProps.JMX_PORT;
import static com.allogy.hyperjetty.ServletProps.NAME;
import static com.allogy.hyperjetty.ServletProps.PATH;
import static com.allogy.hyperjetty.ServletProps.PERM_SIZE;
import static com.allogy.hyperjetty.ServletProps.PID;
import static com.allogy.hyperjetty.ServletProps.SERVICE_PORT;
import static com.allogy.hyperjetty.ServletProps.STACK_SIZE;
import static com.allogy.hyperjetty.ServletProps.ORIGINAL_WAR;
import static com.allogy.hyperjetty.ServletProps.VERSION;

/**
 * User: robert
 * Date: 2013/05/13
 * Time: 12:55 PM
 */
public class Service implements Runnable
{

    private static final String SERVLET_STOPPED_PID = "-1";

    private final File libDirectory;
    private final File logDirectory;
    private final ServerSocket serverSocket;
    private final PrintStream log;

    private File jettyRunnerJar=new File("lib/jetty-runner.jar");
    private File jettyJmxJar   =new File("lib/jetty-jmx.jar");
    private File jettyJmxXml   =new File("lib/jetty-jmx.xml");

    public
    Service(int controlPort, File libDirectory, File logDirectory, String jettyRunnerJar) throws IOException
    {
        this.serverSocket = new ServerSocket(controlPort);
        this.libDirectory = libDirectory;
        this.logDirectory = logDirectory;

        if (jettyRunnerJar!=null)
        {
            this.jettyRunnerJar = new File(jettyRunnerJar);
        }

        assertWritableDirectory(libDirectory);
        assertWritableDirectory(logDirectory);

        mustBeReadableFile(this.jettyRunnerJar);

        log=System.out;
    }

    public int minimumServicePort =10000;
    public int minimumJMXPort     =11000;

    private
    void assertWritableDirectory(File directory)
    {
        if (!directory.isDirectory() || !directory.canWrite())
        {
            throw new IllegalArgumentException("not a writable directory: "+directory);
        }
    }

    public static
    void main(String[] args)
    {
        String controlPort    = systemPropertyOrEnvironment("CONTROL_PORT"    , null);
        String libDirectory   = systemPropertyOrEnvironment("LIB_DIRECTORY"   , null);
        String logDirectory   = systemPropertyOrEnvironment("LOG_DIRECTORY"   , null);
        String jettyRunnerJar = systemPropertyOrEnvironment("JETTY_RUNNER_JAR", null);

        String jettyJmxJar = systemPropertyOrEnvironment("JETTY_JMX_JAR", null);
        String jettyJmxXml = systemPropertyOrEnvironment("JETTY_JMX_XML", null);

        {
            Iterator<String> i = Arrays.asList(args).iterator();

            while (i.hasNext())
            {
                String flag=i.next().toLowerCase();
                String argument=null;

                try {
                    argument=i.next();
                } catch (NoSuchElementException e) {
                    die("Flag: '" + flag + "' requires one argument");
                }

                if (flag.contains("-log"))
                {
                    logDirectory=argument;
                }
                else if (flag.contains("-lib"))
                {
                    libDirectory=argument;
                }
                else if (flag.contains("-port") || flag.contains("-control"))
                {
                    controlPort=argument;
                }
                else if (flag.contains("-runner-jar"))
                {
                    jettyRunnerJar=argument;
                }
                else if (flag.contains("-jmx-jar"))
                {
                    jettyJmxJar=argument;
                }
                else if (flag.contains("-jmx-xml"))
                {
                    jettyJmxXml=argument;
                }
                else
                {
                    die("unrecognized flag: " + flag);
                }
            }
        }

        {
            String NAME="hyperjetty";
            String usage="\n\nusage: "+NAME+" --lib /var/lib/"+NAME+" --log /var/log/"+NAME+" --port 1234 --jetty-runner-jar /usr/libexec/"+NAME+"/jetty-runner.jar";
            if (controlPort   ==null) die("controlPort is unspecified"   +usage);
            if (logDirectory  ==null) die("logDirectory is unspecified"  +usage);
            if (libDirectory  ==null) die("libDirectory is unspecified"  +usage);
            // (jettyRunnerJar==null) die("jettyRunnerJar is unspecified"+usage);
        }

        System.err.print("Control Port : "); System.err.println(controlPort );
        System.err.print("Lib Directory: "); System.err.println(libDirectory);
        System.err.print("Log Directory: "); System.err.println(logDirectory);
        System.err.print("Jetty Runner : "); System.err.println(jettyRunnerJar);

        try {
            Service service=new Service(
                    Integer.parseInt(controlPort),
                    new File(libDirectory),
                    new File(logDirectory),
                    jettyRunnerJar
            );

            if (jettyJmxJar!=null)
            {
                mustBeReadableFile(service.jettyJmxJar = new File(jettyJmxJar));
            }

            if (jettyJmxXml!=null)
            {
                mustBeReadableFile(service.jettyJmxXml = new File(jettyJmxXml));
            }

            service.run();
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(2);
        }
    }

    private static
    void mustBeReadableFile(File file)
    {
        if (file==null)
        {
            throw new IllegalArgumentException("specified file cannot be null");
        }
        if (!file.canRead() || !file.isFile())
        {
            throw new IllegalArgumentException("not readable: "+file);
        }
    }

    /**
     * If true, then running on a system where /proc/$PID has useful information, otherwise
     * running on a OS-X development machine.
     */
    private static boolean LINUX=true;

    static
    {
        LINUX=!new File("/System/Library").exists();
    }

    private static
    void die(String s)
    {
        System.err.println(s);
        System.exit(1);
    }

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

    private boolean alive=true;

    @Override
    public void run()
    {
        provisionallyLaunchAllPreviousServlets();

        log.println("Ready to receive connections...");

        while (alive)
        {
            /*
             * NB: We process all connections ONE-AT-A-TIME, otherwise we would have to do much locking & concurrency control
             */
            Socket socket=null;
            try {
                socket=serverSocket.accept();
                processClientSocketCommand(socket.getInputStream(), socket.getOutputStream());
            } catch (Exception e) {
                e.printStackTrace();
                try {
                    socket.getOutputStream().write(e.toString().getBytes());
                } catch (IOException e1) {
                    //could just be a premature socket closing...
                    log.println(e1.toString());
                }
            } finally {
                try {
                    if (socket!=null)
                    {
                        socket.close();
                    }
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            }

        }
    }

    private
    void provisionallyLaunchAllPreviousServlets()
    {
        logDate();
        log.println("Service is being restored, launching all previous servlets...");

        for (File file : libDirectory.listFiles())
        {
            String name=file.getName();

            if (name.endsWith(".config"))
            {
                try {
                    Properties properties=propertiesFromFile(file);

                    if (isRunning(properties))
                    {
                        log.println("Already running: "+file);
                    }
                    else
                    {
                        int port=Integer.parseInt(properties.getProperty(SERVICE_PORT.toString()));
                        log.println("Launching servlet on port "+port+": "+file);
                        actuallyLaunchServlet(port);
                    }

                } catch (Exception e) {
                    System.err.println("Could not load & launch: "+file);
                    e.printStackTrace();
                }
            }
        }

    }

    private
    Properties propertiesFromFile(File file) throws IOException
    {
        InputStream inputStream=new FileInputStream(file);
        Properties properties=new Properties();
        properties.load(inputStream);
        inputStream.close();

        properties.setProperty("SELF", file.getCanonicalPath());

        return properties;
    }

    private
    Properties propertiesForService(int servicePort) throws IOException
    {
        return propertiesFromFile(configFileForServicePort(servicePort));
    }

    private
    File configFileForServicePort(int servicePort)
    {
        return new File(libDirectory, servicePort+".config");
    }

    private
    File warFileForServicePort(int servicePort)
    {
        return new File(libDirectory, servicePort+".war");
    }

    /**
     * Re-reads the configuration file (which as a side effect ensures that it is sufficient to start the service),
     * and activates the servlet on the specified port.
     *
     * @param servicePort - the primary port that the servlet will serve from
     */
    private
    int actuallyLaunchServlet(int servicePort) throws IOException
    {
        File configFile=configFileForServicePort(servicePort);
        File warFile   =warFileForServicePort(servicePort);

        Properties p=propertiesFromFile(configFile);

        String logFileBase= logFileBaseFromProperties(p);

        String logFile=logFileBase+".log";
        String accessLog=logFileBase+".access";

        StringBuilder sb=new StringBuilder();

        sb.append("{ exec "); //because an ampersand at the end does not work with all the redirection, and we *can't* have the extra process!!!
        sb.append(bestGuessBinary("nohup")).append(' ');
        sb.append(bestGuessBinary("java" ));

        String arg;

        if ((arg=p.getProperty(HEAP_SIZE.toString()))!=null)
        {
            sb.append(" -Xmx").append(arg);
        }

        if ((arg=p.getProperty(STACK_SIZE.toString()))!=null)
        {
            sb.append(" -Xss").append(arg);
        }

        if ((arg=p.getProperty(PERM_SIZE.toString()))!=null)
        {
            sb.append(" -XX:MaxPermSize=").append(arg);
        }

        if ((arg=p.getProperty(JMX_PORT.toString()))!=null)
        {
            sb.append(" -Dcom.sun.management.jmxremote.port=").append(arg);
            sb.append(" -Dcom.sun.management.jmxremote.authenticate=false");
            sb.append(" -Dcom.sun.management.jmxremote.ssl=false");
        }

        sb.append(" -Dvisualvm.display.name=").append(debugProcessNameWithoutSpaces(p));

        if (jettyJmxJar!=null && jettyJmxJar.exists())
        {
            sb.append(" -cp ").append(jettyJmxJar).append(':').append(jettyRunnerJar);
            sb.append(" org.mortbay.jetty.runner.Runner");
        }
        else
        {
            sb.append(" -jar ").append(jettyRunnerJar);
        }

        sb.append(" --stats unsecure");

        if (jettyJmxXml!=null && jettyJmxXml.exists())
        {
            sb.append(" --config ").append(jettyJmxXml);
        }

        if ((arg=p.getProperty(SERVICE_PORT.toString()))!=null)
        {
            sb.append(" --port ").append(arg);
        }

        if ((arg=p.getProperty(PATH.toString()))!=null)
        {
            sb.append(" --path ").append(arg);
        }

        sb.append(" --log ").append(accessLog);

        sb.append(" ").append(warFile.toString());

        sb.append(" >> ").append(logFile).append(" 2>&1 & }; echo $!");

        String command=sb.toString();

        log.println("launch command: "+command);

        printLaunchWarningAndDate(logFile);

        List<String> commandLine=new ArrayList<String>();

        commandLine.add("/bin/bash");
        commandLine.add("-m");
        commandLine.add("-c");
        commandLine.add(command);

        ProcessBuilder processBuilder=new ProcessBuilder(commandLine);

        processBuilder.redirectErrorStream(true);

        Process process=processBuilder.start();

        /*
        Process process = Runtime.getRuntime().exec(new String[]{
                "/bin/bash",
                "-c",
                command
        });
        */

        BufferedReader br=new BufferedReader(new InputStreamReader(process.getInputStream()));

        String pidString = null;
        String line;
        while ((line = br.readLine()) !=null)
        {
            if (pidString==null)
            {
                pidString=line;
            }
            log.println("process: "+line);
        }
        br.close();

        try {
            int i=process.waitFor();
            log.println("waitFor returns status="+i);
            Thread.sleep(50);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        int pid=Integer.parseInt(pidString);

        p.setProperty(PID.toString(), pidString);

        if (isRunning(p))
        {
            log.println("apparently successful launch of pid: "+pidString);
            writeProperties(p, configFile);
        }
        else
        {
            throw new IllegalStateException("failure to launch: "+pidString);
        }

        return pid;
    }

    private
    String debugProcessNameWithoutSpaces(Properties p)
    {
        StringBuilder sb=new StringBuilder();

        sb.append(p.get(NAME.toString()));

        if (p.containsKey(VERSION.toString())) {
            sb.append('-');
            sb.append(p.get(VERSION.toString()));
        }

        sb.append('-');
        sb.append(p.getProperty(SERVICE_PORT.toString()));

        sb.append('-');
        sb.append(p.getProperty(PATH.toString()));

        int i;

        while ((i=sb.indexOf(" "))>=0)
        {
            sb.replace(i, i+1, "-");
        }

        return sb.toString();
    }

    private
    void printLaunchWarningAndDate(String logFile) throws IOException
    {
        StringBuilder message=new StringBuilder();

        message.append("\n\n-----Servlet Launch Attempt-----\n\n");
        message.append(iso_8601_ish.format(new Date()));
        message.append(" - attempting to launch servlet\n");

        boolean append=true;
        FileOutputStream fos=new FileOutputStream(logFile, append);
        fos.write(message.toString().getBytes());
        fos.close();
    }

    private
    String bestGuessBinary(String s) throws IOException
    {
        File file=new File(usrBin, s);
        if (file.exists())
        {
            //return file.getCanonicalPath();
            return s;
        }
        else
        {
            return s;
        }
    }

    private static final File usrBin=new File("/usr/bin");

    private
    String logFileBaseFromProperties(Properties p)
    {
        String appName=p.getProperty(NAME.toString(), "no-name");
        String portNumber=p.getProperty(SERVICE_PORT.toString(), "no-port");
        String basename=niceFileCharactersOnly(appName+"-"+portNumber);

        return new File(logDirectory, basename).toString();
    }

    private
    String niceFileCharactersOnly(String s)
    {
        StringBuilder sb=new StringBuilder();
        int l=s.length();

        for (int i=0; i<l; i++)
        {
            char c=s.charAt(i);

            if (
                 ( c >= 'a' && c <= 'z' ) ||
                 ( c >= 'A' && c <= 'Z' ) ||
                 ( c >= '0' && c <= '9' ) ||
                 ( c == '.' ) ||
                 ( c == '-' ) ||
                 ( c == '_' )
               )
            {
                sb.append(c);
            }
        }

        return sb.toString();
    }

    private
    boolean isRunning(Properties properties)
    {
        int pid=pid(properties);

        if (pid<0)
        {
            ///!!!: bug: setting the pid<=1 should indicate that the servlet should *NOT* be restarted automatically
            log.println("special pid indicates last state was not running / stopped: "+pid);
            return false;
        }

        if (LINUX)
        {
            File file=new File("/proc/"+pid+"/cmdline");
            if (file.exists())
            {
                log.println("found: "+file);
                try {
                    String contents=fileContentsAsString(file);
                    if (contents==null || contents.contains("java"))
                    {
                        log.println("pid "+pid+" is present and has java marker: "+file);
                        return true;
                    }
                    else
                    {
                        log.println("pid "+pid+" is present, but has no java marker: "+file);
                        return false;
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    return true; //cannot verify the command name, but matching PID is sufficient, I guess...
                }
            }
            else
            {
                log.println("dne: "+file);
                return false;
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
                        return true;
                    }
                    else
                    {
                        log.println("pid "+pid+" is present, but lacks java marker");
                        return false;
                    }
                }
                else
                {
                    log.println("pid "+pid+" is not present");
                    return false;
                }
            } catch (IOException e) {
                e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
                return false;
            } catch (InterruptedException e) {
                e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
                return false;
            }
        }
    }

    private
    int pid(Properties properties)
    {
        String pid = properties.getProperty("PID");

        if (pid==null)
        {
            String self=properties.getProperty("SELF", "<unknown>");
            throw new IllegalStateException("no PID in control file/properties: "+self);
        }

        return Integer.parseInt(pid);
    }

    private
    String fileContentsAsString(File file) throws FileNotFoundException
    {
        return new Scanner(file).useDelimiter("\\Z").next();
    }

    private
    void processClientSocketCommand(InputStream inputStream, OutputStream outputStream) throws IOException
    {
        logDate();
        log.println("Got client connection");

        final PrintStream out=new PrintStream(outputStream);
        ObjectInputStream in=new ObjectInputStream(inputStream);

        int numArgs=in.readInt();
        List<String> args=new ArrayList(numArgs);

        //The first arg is the command
        String command=in.readUTF();

        for (int i=1; i<numArgs; i++)
        {
            String arg=in.readUTF();
            args.add(arg);
        }

        int numFiles=in.readInt();

        log.println(numFiles+" file(s) coming with "+command+" command");

        if (command.equals("ping"))
        {
            out.print("GOOD\npong\n");
        }
        else if (command.equals("launch"))
        {
            doLaunchCommand(args, in, out, numFiles);
        }
        else if (command.equals("stop"))
        {
            doStopCommand(args, in, out);
        }
        else
        {
            String message="Unknown command: "+command;
            out.println(message);
            log.println(message);
        }
    }

    private
    void doStopCommand(List<String> args, ObjectInputStream in, PrintStream out) throws IOException
    {
        String port=null;
        String path=null;
        String name=null;
        String wait=null;

        Iterator<String> i=args.iterator();

        while (i.hasNext())
        {
            String flag=i.next();
            String argument;

            try {
                argument=i.next();
            } catch (NoSuchElementException e) {
                throw new IllegalArgumentException(flag+" requires one argument", e);
            }

            if (flag.contains("-port"))
            {
                port=argument;
            }
            else if (flag.contains("-name"))
            {
                name=argument;
            }
            else if (flag.contains("-path"))
            {
                path=argument;
            }
            else if (flag.contains("-wait"))
            {
                wait=argument;
            }
            else
            {
                String message="Unknown flag: "+flag;
                log.println(message);
                out.println(message);
                return;
            }
        }

        if (port==null)
        {
            String message="Stop command requires port to be specified";
            log.println(message);
            out.println(message);
            return;
        }

        if (name!=null || path!=null)
        {
            String message="Stop command presently only supports stop-by-path";
            log.println(message);
            out.println(message);
            return;
        }

        int servicePort=Integer.parseInt(port);

        Properties p = propertiesForService(servicePort);

        int pid=pid(p);

        int jmxPort=Integer.parseInt(p.getProperty(JMX_PORT.toString()));

        log.println("pid="+pid+", jmx="+jmxPort);

        if ( pid<=1 || !isRunning(p))
        {
            log.println("Already terminated?");
            out.println("GOOD\nServer is already shutdown or dead");
            return;
        }

        try {

            JMXUtils.printMemoryUsageGivenJMXPort(jmxPort);

            JMXUtils.tellJettyContainerToStopAtJMXPort(jmxPort);

            if (trueish(wait))
            {
                log.println("waiting for termination of pid: "+pid);
                while (isRunning(p))
                {
                    log.println("still running: "+pid);
                    Thread.sleep(250);
                }
            }

            out.println("GOOD\nShutdown command has been sent");

            p.setProperty(PID.toString(), SERVLET_STOPPED_PID);
            writeProperties(p, configFileForServicePort(servicePort));

        } catch (Throwable t) {
            t.printStackTrace();
            out.println(t.toString());
        }
    }

    private boolean trueish(String s)
    {
        if (s==null || s.length()==0) return false;
        char c=s.charAt(0);
        return (c=='t' || c=='T' || c=='1' || c=='y' || c=='Y');
    }

    private static final DateFormat iso_8601_ish = new SimpleDateFormat("yyyy-MM-dd HH:mm'z'");

    static
    {
        TimeZone timeZone=TimeZone.getTimeZone("UTC");
        Calendar calendar=Calendar.getInstance(timeZone);
        iso_8601_ish.setCalendar(calendar);
    }

    private void logDate()
    {
        Date now=new Date();
        log.println();
        log.println(iso_8601_ish.format(now));
    }

    private
    void doLaunchCommand(List<String> args, ObjectInputStream in, PrintStream out, int numFiles) throws IOException
    {
        String war=null;
        String name=null;
        String path=null;
        String dry=null;

        Iterator<String> i=args.iterator();

        while (i.hasNext())
        {
            String flag=i.next();
            String argument;
            try {
                argument=i.next();
            } catch (NoSuchElementException e) {
                throw new IllegalArgumentException(flag+" requires one argument", e);
            }
            if (flag.contains("-war"))
            {
                war=argument;
            }
            else if (flag.contains("-name"))
            {
                name=argument;
            }
            else if (flag.contains("-path"))
            {
                path=argument;
            }
            else if (flag.contains("-dry"))
            {
                dry=argument;
            }
            else
            {
                String message="Unknown option flag: "+flag;
                out.println(message);
                log.println(message);
                return;
            }
        }

        if (war==null)
        {
            out.println("war-file not specified, use the '--war' flag to do so");
            return;
        }

        if (name==null)
        {
            out.println("application name not specified, use the '--name' flag to do so; remember to set the '--path' too");
            return;
        }

        if (path==null)
        {
            out.println("path not specified, you probably want to use '/' or the instance name");
            return;
        }

        if (numFiles!=1)
        {
            out.println("expecting precisely one file... the war-file; make sure no other command-line args are file names");
            log.println("client supplied "+numFiles+" files");
            return;
        }

        log.println("WAR ="+war );
        log.println("NAME="+name);
        log.println("PATH="+path);

        String originalFilename=in.readUTF();

        if (!originalFilename.equals(war))
        {
            log.println("WARN: war != filename: "+originalFilename);
        }

        if (!originalFilename.endsWith(".war"))
        {
            String message="cowardly refusing to process war file that does not end in '.war', if you don't know what you are doing please ask for help!";
            log.println(message);
            out.println(message);
            return;
        }

        PortReservation portReservation=PortReservation.startingAt(minimumServicePort, minimumJMXPort);

        int servicePort=portReservation.getServicePort();
        int jmxPort=portReservation.getJmxPort();

        log.println("PORT="+servicePort);
        log.println("JMX ="+jmxPort);

        long bytes=in.readLong();

        File warFile   =warFileForServicePort(servicePort);
        File configFile=configFileForServicePort(servicePort);

        log.println("receiving: "+originalFilename+" "+bytes+" bytes => "+warFile);

        FileOutputStream fos=new FileOutputStream(warFile);

        int max=4096;
        byte[] buffer=new byte[max];

        long bytesToGo=bytes;
        int read=max;

        while ((read=in.read(buffer, 0, read))>0)
        {
            fos.write(buffer, 0, read);
            bytesToGo-=read;
            read=(int)Math.min(bytesToGo, max);
        }

        fos.flush();
        fos.close();

        logDate();
        log.println("finished writing: "+warFile);

        Properties p=null;

        try {
            p= getEmbeddedAppProperties(warFile);
        } catch (Exception e) {
            log.println("Unable to read properties from war file");
            e.printStackTrace();
        }

        if (p==null)
        {
            log.println("INFO: the war file has no embedded servlet properties");
            p=new Properties();
        }

        maybeSet(p, SERVICE_PORT, Integer.toString(servicePort));
        maybeSet(p, JMX_PORT, Integer.toString(jmxPort));
        maybeSet(p, ORIGINAL_WAR, war);
        maybeSet(p, NAME, name);
        maybeSet(p, PATH, path);

        //FROM: http://tapestry.apache.org/specific-errors-faq.html
        maybeSet(p, HEAP_SIZE , "600m"); // -Xmx
        maybeSet(p, PERM_SIZE , "512m"); // -XX:MaxPermSize=
        maybeSet(p, STACK_SIZE, "1m"  ); // -Xss

        maybeSet(p, PID, "-1");

        writeProperties(p, configFile);

        portReservation.release();

        if (dry!=null && dry.toLowerCase().equals("true"))
        {
            log.println("not launching servlet, as dry option is true");
            out.print("GOOD\ndry\n");
        }
        else
        {
            int pid=actuallyLaunchServlet(servicePort);
            out.print("GOOD\n" + pid + "\n");
        }
    }

    private
    void writeProperties(Properties p, File configFile) throws IOException
    {


        FileOutputStream fos=new FileOutputStream(configFile);
        p.store(fos, "Written by hyperjetty service class");
        fos.close();
    }

    private
    Properties getEmbeddedAppProperties(File warFile) throws IOException
    {
        JarFile jarFile=new JarFile(warFile);
        try {
            ZipEntry zipEntry = jarFile.getEntry("WEB-INF/app.properties");
            if (zipEntry==null) {
                log.println("No app.properties");
                return null;
            }
            InputStream inputStream=jarFile.getInputStream(zipEntry);
            if (inputStream==null)
            {
                log.println("cannot get inputstream for app.properties");
                return null;
            }
            else
            {
                try {
                    Properties retval=new Properties();
                    retval.load(inputStream);
                    log.println("read "+retval.size()+" properties from embedded app.properties file");
                    return retval;
                } finally {
                    inputStream.close();
                }
            }
        }  finally {
            jarFile.close();
        }
    }

    private static
    void maybeSet(Properties p, ServletProps keyCode, String ifNotPresent)
    {
        String key=keyCode.toString();
        if (!p.containsKey(key))
        {
            p.setProperty(key, ifNotPresent);
        }
    }
}
