package com.allogy.hyperjetty;

import javax.management.*;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

import static com.allogy.hyperjetty.ServletProps.DATE_CREATED;
import static com.allogy.hyperjetty.ServletProps.DATE_STARTED;
import static com.allogy.hyperjetty.ServletProps.HEAP_SIZE;
import static com.allogy.hyperjetty.ServletProps.JMX_PORT;
import static com.allogy.hyperjetty.ServletProps.NAME;
import static com.allogy.hyperjetty.ServletProps.OPTIONS;
import static com.allogy.hyperjetty.ServletProps.ORIGINAL_WAR;
import static com.allogy.hyperjetty.ServletProps.PATH;
import static com.allogy.hyperjetty.ServletProps.PERM_SIZE;
import static com.allogy.hyperjetty.ServletProps.PID;
import static com.allogy.hyperjetty.ServletProps.PORT_NUMBER_IN_LOG_FILENAME;
import static com.allogy.hyperjetty.ServletProps.SERVICE_PORT;
import static com.allogy.hyperjetty.ServletProps.STACK_SIZE;
import static com.allogy.hyperjetty.ServletProps.TAGS;
import static com.allogy.hyperjetty.ServletProps.VERSION;

/**
 * User: robert
 * Date: 2013/05/13
 * Time: 12:55 PM
 */
public class Service implements Runnable
{

    private static final String SERVLET_STOPPED_PID = "-1";
    private static final long RESTART_DELAY_MS = 20;
    private static final boolean USE_BIG_TAPESTRY_DEFAULTS = false;

    private static final int JETTY_VERSION = 8;

    private final File libDirectory;
    private final File etcDirectory;
    private final File logDirectory;
    private final ServerSocket serverSocket;
    private final PrintStream log;

    private File jettyRunnerJar=new File("lib/jetty-runner.jar");
    private File jettyJmxJar   =new File("lib/jetty-jmx.jar");
    private File jettyJmxXml   =new File("etc/jetty-jmx.xml");

    public
    Service(int controlPort, File libDirectory, File etcDirectory, File logDirectory, String jettyRunnerJar) throws IOException
    {
        this.serverSocket = new ServerSocket(controlPort);
        this.libDirectory = libDirectory;
        this.etcDirectory = etcDirectory;
        this.logDirectory = logDirectory;

        if (jettyRunnerJar!=null)
        {
            this.jettyRunnerJar = new File(jettyRunnerJar);
        }

        assertWritableDirectory(libDirectory);
        assertWritableDirectory(etcDirectory);
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
        String etcDirectory   = systemPropertyOrEnvironment("ETC_DIRECTORY"   , null);
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

                if (flag.contains("-etc"))
                {
                    etcDirectory=argument;
                }
                else if (flag.contains("-log"))
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
            if (etcDirectory  ==null) die("etcDirectory is unspecified"  +usage);
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
                    new File(etcDirectory),
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

                readAnyRemainingInput(socket); //e.g. files we did not use

                try {
                    OutputStream outputStream=socket.getOutputStream();
                    outputStream.write(e.toString().getBytes());
                    outputStream.flush();
                    outputStream.close();
                } catch (IOException e1) {
                    //could just be a premature socket closing...
                    log.println("prematurely closing socket?");
                    log.println(e1.toString());
                }
            } finally {
                try {
                    if (socket!=null)
                    {
                        socket.close();
                    }
                } catch (Throwable t) {
                    log.println("unable to close client socket (already closed?)");
                    t.printStackTrace();
                }
            }

        }
    }

    private void readAnyRemainingInput(Socket socket)
    {
        log.println("readAnyRemainingInput");
        try {
            InputStream inputStream = socket.getInputStream();
            byte[] bytes=new byte[4096];
            while (inputStream.read(bytes)>=0)
            {
                //do nothing
            }
        } catch (IOException e) {
            log.println("while readAnyRemainingInput:");
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
        }
    }

    private
    void provisionallyLaunchAllPreviousServlets()
    {
        logDate();
        log.println("Service is being restored, launching all previous servlets...");

        for (File file : etcDirectory.listFiles())
        {
            String name=file.getName();

            if (name.endsWith(".config"))
            {
                try {
                    Properties properties=propertiesFromFile(file);
                    int pid=pid(properties);

                    if (pid<=1)
                    {
                        log.println("Was stopped: "+file+" ("+pid+")");
                    }
                    else if (isRunning(properties))
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

    /*
    private
    Properties propertiesForService(int servicePort) throws IOException
    {
        return propertiesFromFile(configFileForServicePort(servicePort));
    }
    */

    private
    File configFileForServicePort(int servicePort)
    {
        return new File(etcDirectory, servicePort+".config");
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

        LaunchOptions launchOptions=new LaunchOptions(libDirectory);

        {
            String launchOptionsCsv=p.getProperty(OPTIONS.toString());
            if (launchOptionsCsv!=null)
            {
                String[] optionBits=launchOptionsCsv.split(",");
                for (String option : optionBits) {
                    log.println("launch option: enable: "+option);
                    launchOptions.enable(option);
                }
            }
        }

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
            if (jettyJmxXml.exists())
            {
                /**
                 * This mode is preferred b/c it behaves well with firewalls & port forwarding, the
                 * only downside is that one must manually uncomment section of config (on use an
                 * un-pristine default config), see:
                 *
                 * http://wiki.eclipse.org/Jetty/Tutorial/JMX
                 */
                sb.append(" -Djetty.jmxrmiport=").append(arg);
                launchOptions.addJettyConfig(jettyJmxXml);
            }
            else
            {
                sb.append(" -Dcom.sun.management.jmxremote.port=").append(arg);
                sb.append(" -Dcom.sun.management.jmxremote.authenticate=false");
                sb.append(" -Dcom.sun.management.jmxremote.ssl=false");
            }
        }

        sb.append(" -Dvisualvm.display.name=").append(debugProcessNameWithoutSpaces(p));

        sb.append(launchOptions.getJavaDefines());

        if (jettyJmxJar!=null && jettyJmxJar.exists())
        {
            launchOptions.addJar(jettyJmxJar);
        }

        launchOptions.addJar(jettyRunnerJar);

        sb.append(" -cp ");

        launchOptions.appendClassPath(sb);

        if (JETTY_VERSION > 8)
        {
            sb.append(" org.eclipse.jetty.runner.Runner");
        }
        else
        {
            sb.append(" org.mortbay.jetty.runner.Runner");
        }

        sb.append(" --stats unsecure"); //starts a "/stats" servlet... probably harmless (minor overhead)

        for (String jettyConfigFile : launchOptions.jettyConfigFiles)
        {
            sb.append(" --config ").append(jettyConfigFile);
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

        tagPresentDate(p, DATE_STARTED);
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
        if (falseish(p, PORT_NUMBER_IN_LOG_FILENAME))
        {
            String appName=p.getProperty(NAME.toString(), "no-name");
            String basename=niceFileCharactersOnly(appName);

            return new File(logDirectory, basename).toString();
        }
        else
        {
            String appName=p.getProperty(NAME.toString(), "no-name");
            String portNumber=p.getProperty(SERVICE_PORT.toString(), "no-port");
            String basename=niceFileCharactersOnly(appName+"-"+portNumber);

            return new File(logDirectory, basename).toString();
        }
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
        return isRunning(pid);
    }

    private
    boolean isRunning(int pid)
    {
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

        log.print("* command: ");
        log.print(command);

        for (int i=1; i<numArgs; i++)
        {
            String arg=in.readUTF();
            args.add(arg);
            log.print(' ');
            log.print(arg);
        }
        log.println();

        int numFiles=in.readInt();

        if (numFiles>0)
        {
            log.println(numFiles+" file(s) coming with "+command+" command");
        }

        // ------------------------------------------- CLIENT COMMANDS --------------------------------------------

        if (command.startsWith("access-log"))
        {
            dumpLogFileNames(getFilter(args), out, ".access");
        }
        else if (command.equals("dump"))
        {
            dumpPropertiesOfOneMatchingServlet(getFilter(args), out);
        }
        else if (command.equals("launch"))
        {
            doLaunchCommand(args, in, out, numFiles);
        }
        else if (command.startsWith("log"))
        {
            dumpLogFileNames(getFilter(args), out, ".log");
        }
        else if (command.startsWith("ls-pid"))
        {
            dumpSpecificKey(getFilter(args), out, PID);
        }
        else if (command.startsWith("ls-port"))
        {
            dumpSpecificKey(getFilter(args), out, SERVICE_PORT);
        }
        else if (command.startsWith("ls-jmx"))
        {
            dumpSpecificKey(getFilter(args), out, JMX_PORT);
        }
        else if (command.startsWith("ls-tag"))
        {
            dumpUniqueMultiKey(getFilter(args), out, TAGS);
        }
        else if (command.startsWith("ls-version"))
        {
            dumpSpecificKey(getFilter(args), out, VERSION);
        }
        else if (command.equals("nginx-routing"))
        {
            dumpNginxRoutingTable(getFilter(args), out);
        }
        else if (command.equals("ping"))
        {
            out.print("GOOD\npong\n");
        }
        else if (command.equals("remove"))
        {
            doRemoveCommand(getFilter(args), out);
        }
        else if (command.equals("start"))
        {
            doStartCommand(getFilter(args), out);
        }
        else if (command.equals("set"))
        {
            doSetCommand(getFilter(args), out);
        }
        else if (command.equals("stop"))
        {
            doStopCommand(getFilter(args), out);
        }
        else if (command.startsWith("stat")) //"status", "stats", "statistics", or even "stat" (like the unix function)
        {
            doStatsCommand(getFilter(args), out);
        }
        else
        {
            String message="Unknown command: "+command;
            out.println(message);
            log.println(message);
        }
        // ------------------------------------------- END CLIENT COMMANDS --------------------------------------------
    }

    private
    void dumpPropertiesOfOneMatchingServlet(Filter filter, PrintStream out) throws IOException
    {
        List<Properties> matches = propertiesFromMatchingConfigFiles(filter);

        if (matches.size()!=1)
        {
            out.println("expecting precisely one servlet match, found "+matches.size());
            return;
        }

        out.println("GOOD");

        Properties properties=matches.get(0);

        int servicePort=Integer.parseInt(properties.getProperty(SERVICE_PORT.toString()));

        properties.store(out, null);

        String logBase = logFileBaseFromProperties(properties);
        out.println("LOG="+logBase+".log");
        out.println("ACCESS_LOG="+logBase+".access");
        out.println("CONFIG_FILE="+configFileForServicePort(servicePort));
        out.println("WAR_FILE="+warFileForServicePort(servicePort));

        int pid=pid(properties);
        out.println("PID="+pid);

        if (pid<=1)
        {
            out.println("STATE=Stopped");
        }
        else if (isRunning(properties))
        {
            out.println("STATE=Alive");
        }
        else
        {
            out.println("STATE=Dead");
        }

        String host=filter.getOption("host", "localhost");
        String protocol=filter.getOption("protocol", "http");
        String path=properties.getProperty(PATH.toString());

        out.println("URL="+protocol+"://"+host+":"+servicePort+path);
    }

    private
    void dumpUniqueMultiKey(Filter filter, PrintStream out, ServletProps key) throws IOException
    {
        List<Properties> matches = propertiesFromMatchingConfigFiles(filter);

        if (matches.isEmpty())
        {
            String message = "no matching servlets";
            out.println(message);
            log.println(message);
            return;
        }

        String keyString=key.toString();
        Set<String> values=new HashSet<String>();

        for (Properties properties : matches)
        {
            String value=properties.getProperty(keyString);
            if (value!=null)
            {
                if (value.indexOf(',')>=0)
                {
                    String[] multi=value.split(",");
                    for (String s : multi) {
                        values.add(s);
                    }

                }
                else
                {
                    values.add(value);
                }
            }
        }

        out.println("GOOD");

        for (String s : values)
        {
            out.println(s);
        }

    }

    private
    void dumpSpecificKey(Filter filter, PrintStream out, ServletProps key) throws IOException
    {
        List<Properties> matches = propertiesFromMatchingConfigFiles(filter);

        if (matches.isEmpty())
        {
            String message = "no matching servlets";
            out.println(message);
            log.println(message);
            return;
        }

        String keyString=key.toString();

        out.println("GOOD");

        for (Properties p : matches)
        {
            String value=p.getProperty(keyString);
            if (value!=null)
            {
                out.println(value);
            }
        }

    }

    private void doSetCommand(Filter matchingFilter, PrintStream out) throws IOException
    {
        Filter setFilter=matchingFilter.kludgeSetFilter;

        if (setFilter==null || matchingFilter.implicitlyMatchesEverything())
        {
            throw new UnsupportedOperationException("missing (or empty) where clause");
        }

        if (setFilter.implicitlyMatchesEverything())
        {
            throw new UnsupportedOperationException("missing (or empty) set clause");
        }

        List<Properties> matches = propertiesFromMatchingConfigFiles(matchingFilter);

        if (matches.isEmpty())
        {
            String message = "no matching servlets";
            out.println(message);
            log.println(message);
            return;
        }

        int total=matches.size();

        if (setFilter.setCanOnlyBeAppliedToOnlyOneServlet() && total > 1)
        {
            throw new UnsupportedOperationException("requested set operation can only be applied to one servlet, but "+total+" matched");
        }

        int success=0;
        List<String> failures=new ArrayList<String>();

        for (Properties properties : matches)
        {
            String name=humanReadable(properties);
            try {
                doSetCommand(properties, setFilter);
                success++;
            }
            catch (Throwable t)
            {
                t.printStackTrace();
                String message="cant actuate set command for "+name+": "+t.toString();
                log.println(message);
                failures.add(message);
            }
        }

        successOrFailureReport("reconfigured", total, success, failures, out);
    }

    private
    void doSetCommand(Properties newProperties, Filter setFilter) throws MalformedObjectNameException,
            IntrospectionException, InstanceNotFoundException, IOException, ReflectionException,
            AttributeNotFoundException, MBeanException, InterruptedException
    {
        Properties oldProperties = (Properties) newProperties.clone();
        setFilter.applySetOperationTo(newProperties);

        if (setFilter.port!=null)
        {
            /*
            Restart the world... technically an edge case (b/c we use the service port as a primary key),
            yet oddly simpler.
             */
            int oldServicePort= Integer.parseInt(oldProperties.getProperty(SERVICE_PORT.toString()));
            int newServicePort= Integer.parseInt(newProperties.getProperty(SERVICE_PORT.toString()));

            log.println("warn: changing primary port: "+oldServicePort+" -> "+newServicePort);
            if (isRunning(oldProperties))
            {
                doStopCommand(oldProperties);
                warFileForServicePort(oldServicePort).renameTo(warFileForServicePort(newServicePort));
                //configFileForServicePort(oldServicePort).renameTo(configFileForServicePort(newServicePort));
                writeProperties(newProperties, configFileForServicePort(newServicePort));
                configFileForServicePort(oldServicePort).delete();
                Thread.sleep(RESTART_DELAY_MS);
                actuallyLaunchServlet(newServicePort);
            }
            else
            {
                log.println("servlet was not running, so it will not be running afterwards either");
                warFileForServicePort(oldServicePort).renameTo(warFileForServicePort(newServicePort));
                //configFileForServicePort(oldServicePort).renameTo(configFileForServicePort(newServicePort));
                writeProperties(newProperties, configFileForServicePort(newServicePort));
                configFileForServicePort(oldServicePort).delete();
            }

        }
        else if (setFilter.setRequiresServletRestart() && isRunning(oldProperties))
        {
            log.println("servlet restart required");
            int servicePort= Integer.parseInt(newProperties.getProperty(SERVICE_PORT.toString()));
            doStopCommand(oldProperties);
            writeProperties(newProperties, configFileForServicePort(servicePort));
            Thread.sleep(RESTART_DELAY_MS);
            actuallyLaunchServlet(servicePort);
        }
        else
        {
            log.println("servlet does NOT need to be restarted :-)");
            int servicePort= Integer.parseInt(newProperties.getProperty(SERVICE_PORT.toString()));
            writeProperties(newProperties, configFileForServicePort(servicePort));
        }

    }

    private
    void dumpNginxRoutingTable(Filter filter, PrintStream out) throws IOException
    {
        int    tabs = Integer.parseInt(filter.getOption("tabs", "2"));
        String host = filter.getOption("host", "127.0.0.1");

        // http://wiki.nginx.org/HttpUpstreamModule ("server" sub-section)
        String weight       = filter.getOption("weight"      , null);
        String max_fails    = filter.getOption("max-fails"   , null);
        String fail_timeout = filter.getOption("fail-timeout", null);
        String down         = filter.getOption("down"        , null);
        String backup       = filter.getOption("backup"      , null);

        boolean alwaysDown=false;
        boolean downIfNotRunning=false;

        if (down!=null)
        {
            if (down.equals("auto"))
            {
                downIfNotRunning=true;
            }
            if (down.equals("true"))
            {
                alwaysDown=true;
            }
        }

        List<Properties> matchedProperties = propertiesFromMatchingConfigFiles(filter);

        if (matchedProperties.isEmpty())
        {
            String message = "no matching servlets";
            out.println(message);
            log.println(message);
            return;
        }

        out.println("GOOD");

        for (Properties properties : matchedProperties)
        {
            String servicePort=properties.getProperty(SERVICE_PORT.toString());
            for (int i=0; i<tabs; i++)
            {
                out.print("\t");
            }
            out.print(host);
            out.print(':');
            out.print(servicePort);

            if (weight!=null)
            {
                out.print(" weight=");
                out.print(weight);
            }

            if (max_fails != null)
            {
                out.print(" max_fails=");
                out.print(max_fails);
            }

            if (fail_timeout != null)
            {
                out.print(" fail_timeout=");
                out.print(fail_timeout);
            }

            if (backup != null)
            {
                out.print(" backup");
            }

            if (alwaysDown || (downIfNotRunning && !isRunning(properties)))
            {
                out.print(" down");
            }

            out.println(';');
        }

    }

    private
    void dumpLogFileNames(Filter filter, PrintStream out, String suffix) throws IOException
    {
        out.println("GOOD");

        List<Properties> matchedProperties = propertiesFromMatchingConfigFiles(filter);

        if (matchedProperties.isEmpty())
        {
            String message = "no matching servlets";
            out.println(message);
            log.println(message);
            return;
        }

        for (Properties properties : matchedProperties)
        {
            String filename = logFileBaseFromProperties(properties) + suffix;
            out.println(filename);
            log.println(filename);
        }
    }

    private
    void doStopCommand(Filter filter, PrintStream out) throws IOException
    {
        if (filter.implicitlyMatchesEverything())
        {
            out.println("stop command requires some restrictions or an explicit '--all' flag");
            return;
        }

        int total=0;
        int success=0;

        //List<String> pidsToWaitFor;
        List<String> failures=new ArrayList<String>();

        for (Properties properties : propertiesFromMatchingConfigFiles(filter))
        {
            total++;
            String name=humanReadable(properties);

            try {
                log.println("stopping: "+name);
                int pid=doStopCommand(properties);
                success++;
            } catch (Throwable t) {
                t.printStackTrace();
                String message="failed to stop '"+name+"': "+t.toString();
                failures.add(message);
                log.println(message);
            }
        }

        successOrFailureReport("stopped", total, success, failures, out);
    }

    private
    void doRemoveCommand(Filter filter, PrintStream out) throws IOException
    {
        if (filter.implicitlyMatchesEverything())
        {
            out.println("remove command requires some restrictions or an explicit '--all' flag");
            return;
        }

        int total=0;
        int success=0;

        //List<String> pidsToWaitFor;
        List<String> failures=new ArrayList<String>();

        for (Properties properties : propertiesFromMatchingConfigFiles(filter))
        {
            total++;
            String name=humanReadable(properties);

            try {
                int servicePort = Integer.parseInt(properties.getProperty(SERVICE_PORT.toString()));

                File configFile=configFileForServicePort(servicePort);

                if (!configFile.exists())
                {
                    throw new FileNotFoundException(configFile.toString());
                }

                File warFile=warFileForServicePort(servicePort);

                if (!warFile.exists())
                {
                    throw new FileNotFoundException(warFile.toString());
                }

                log.println("stopping: "+name);
                int pid=doStopCommand(properties);

                configFile.delete();
                warFile.delete();

                success++;
            } catch (Throwable t) {
                t.printStackTrace();
                String message="failed to stop & remove '"+name+"': "+t.toString();
                failures.add(message);
                log.println(message);
            }
        }

        successOrFailureReport("removed", total, success, failures, out);
    }

    private
    void doStartCommand(Filter filter, PrintStream out) throws IOException
    {
        if (filter.implicitlyMatchesEverything())
        {
            out.println("start command requires some restrictions or an explicit '--all' flag");
            return;
        }

        int total=0;
        int success=0;

        //List<String> pidsToWaitFor;
        List<String> failures=new ArrayList<String>();

        for (Properties properties : propertiesFromMatchingConfigFiles(filter))
        {
            total++;
            String name=humanReadable(properties);

            try {
                if (isRunning(properties))
                {
                    String message="already running: "+name;
                    log.println(message);
                    failures.add(message);
                }
                else
                {
                    log.println("starting: "+name);
                    actuallyLaunchServlet(Integer.parseInt(properties.getProperty(SERVICE_PORT.toString())));
                }
                success++;
            } catch (Throwable t) {
                t.printStackTrace();
                String message="failed to start '"+name+"': "+t.toString();
                failures.add(message);
                log.println(message);
            }
        }

        successOrFailureReport("started", total, success, failures, out);
    }

    private
    void successOrFailureReport(String verbed, int total, int success, List<String> failures, PrintStream out)
    {
        if (success==0)
        {
            if (total==0)
            {
                out.println("total failure, filter did not match any servlets");
            }
            else
            {
                out.println("total failure (x"+total+")");
            }
        }
        else if (success==total)
        {
            out.println("GOOD");
            out.println(verbed+" "+total+" servlet(s)");
        }
        else
        {
            int percent=100*success/total;
            out.println(percent + "% compliance, only "+verbed+" "+success+" of "+total+" matching servlet(s)");
        }

        if (!failures.isEmpty())
        {
            out.println();
            for (String s : failures)
            {
                out.println(s);
            }
        }
    }

    private
    String humanReadable(Properties properties)
    {
        String name=properties.getProperty(NAME.toString(), "no-name");
        String port=properties.getProperty(SERVICE_PORT.toString(), "no-port");
        String path=properties.getProperty(PATH.toString(), "/");

        return name+"-"+port+"-"+path;
    }

    private
    int doStopCommand(Properties properties) throws MalformedObjectNameException, ReflectionException,
            IOException, InstanceNotFoundException, AttributeNotFoundException, MBeanException, IntrospectionException
    {
        int pid=pid(properties);
        int servicePort=Integer.parseInt(properties.getProperty(SERVICE_PORT.toString()));
        int jmxPort=Integer.parseInt(properties.getProperty(JMX_PORT.toString()));

        log.println("pid="+pid+", jmx="+jmxPort);

        if ( pid<=1)
        {
            log.println("Already terminated? Don't have to stop this servlet.");
            return -1;
        }
        else if (!isRunning(properties))
        {
            log.println("Stopping a dead process");
            properties.setProperty(PID.toString(), SERVLET_STOPPED_PID);
            writeProperties(properties, configFileForServicePort(servicePort));
            return pid;
        }
        else
        {
            JMXUtils.printMemoryUsageGivenJMXPort(jmxPort);

            //JMXUtils.tellJettyContainerToStopAtJMXPort(jmxPort); hangs on RMI Reaper
            Runtime.getRuntime().exec("kill "+pid); //still runs the shutdown hooks!!!

            properties.setProperty(PID.toString(), SERVLET_STOPPED_PID);
            writeProperties(properties, configFileForServicePort(servicePort));

            return pid;
        }
    }

    //NB: trueish & falseish are not complementary (they both return false for NULL or unknown)
    private boolean trueish(Properties p, ServletProps key)
    {
        String s=p.getProperty(key.toString());
        if (s==null || s.length()==0) return false;
        char c=s.charAt(0);
        return (c=='t' || c=='T' || c=='1' || c=='y' || c=='Y');
    }

    //NB: trueish & falseish are not complementary (they both return false for NULL or unknown)
    private boolean falseish(Properties p, ServletProps key)
    {
        String s=p.getProperty(key.toString());
        if (s==null || s.length()==0) return false;
        char c=s.charAt(0);
        return (c=='f' || c=='F' || c=='0' || c=='n' || c=='N');
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
        Filter filter=getFilter(args);

        /* "Mandatory" arguments */
        String war     = theOneRequired("war" , filter.war );
        String name    = theOneRequired("name", filter.name);
        String path    = theOneRequired("path", filter.path);

        /* Optional arguments */
        String tag         = commaJoinedOrNull("tag"  , filter.tag  );
        String version     = commaJoinedOrNull("version", filter.version);

        String heapMemory  = oneOrNull("heap", filter.heap);
        String permMemory  = oneOrNull("perm", filter.perm);
        String stackMemory = oneOrNull("stack", filter.stack);

        /* Launch options */
        String dry  = filter.getOption("dry", null);
        String returnValue=filter.getOption("return", "port"); //by default, we will print the port number on success

        String portNumberInLogFilename=filter.getOption("port-based-logs", null);

        if (numFiles!=1)
        {
            if (numFiles==0)
            {
                out.println("service side did not receive the war file, make sure it is reachable client side and that you have specified");
            }
            else
            {
                out.println("expecting precisely one file... the war-file; make sure no other command-line args are file names");
            }
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
        int firstAttemptAtAServicePort=servicePort;

        while (configFileForServicePort(servicePort).exists())
        {
            log.println("WARN: port already configured: "+servicePort);
            PortReservation previousReservation=portReservation;
            portReservation=PortReservation.startingAt(minimumServicePort, minimumJMXPort);
            previousReservation.release();
            servicePort=portReservation.getServicePort();
            if (servicePort==firstAttemptAtAServicePort)
            {
                throw new IllegalStateException("attempting to acequire a port reservation wrapped back around to "+servicePort+" this instance is probably 'full'...");
            }
        }

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

        if (version!=null)
        {
            maybeSet(p, VERSION, version);
        }

        if (portNumberInLogFilename!=null)
        {
            p.setProperty(PORT_NUMBER_IN_LOG_FILENAME.toString(), portNumberInLogFilename);
        }

        if (tag!=null)
        {
            p.setProperty(TAGS.toString(), tag);
        }

        maybeSet(p, SERVICE_PORT, Integer.toString(servicePort));
        maybeSet(p, JMX_PORT, Integer.toString(jmxPort));
        maybeSet(p, ORIGINAL_WAR, war);

        if (name!=null)
        {
            p.setProperty(NAME.toString(), name);
        }

        if (path!=null)
        {
            p.setProperty(PATH.toString(), path);
        }

        if (USE_BIG_TAPESTRY_DEFAULTS)
        {
            //FROM: http://tapestry.apache.org/specific-errors-faq.html
            maybeSet(p, HEAP_SIZE , "600m"); // -Xmx
            maybeSet(p, PERM_SIZE , "512m"); // -XX:MaxPermSize=
            maybeSet(p, STACK_SIZE, "1m"  ); // -Xss
        }
        else
        {
            maybeSet(p, HEAP_SIZE , "300m"); // -Xmx
            maybeSet(p, PERM_SIZE , "200m"); // -XX:MaxPermSize=
            maybeSet(p, STACK_SIZE, "1m"  ); // -Xss
        }

        if (heapMemory!=null)
        {
            p.setProperty(HEAP_SIZE.toString(), heapMemory);
        }

        if (permMemory!=null)
        {
            p.setProperty(PERM_SIZE.toString(), permMemory);
        }

        if (stackMemory!=null)
        {
            p.setProperty(STACK_SIZE.toString(), stackMemory);
        }

        maybeSet(p, PID, "-1");

        if (filter.options!=null)
        {
            String options=commaJoinedOrNull("options", filter.options.keySet());
            log.println("launch options: "+options);
            p.setProperty(OPTIONS.toString(), options);
        }

        tagPresentDate(p, DATE_CREATED);
        writeProperties(p, configFile);

        portReservation.release();

        String retval=Integer.toString(servicePort);

        if (dry!=null && dry.toLowerCase().equals("true"))
        {
            log.println("not launching servlet, as dry option is true");
            if (returnValue.equals("pid"))
            {
                retval="dry";
            }
        }
        else
        {
            int pid=actuallyLaunchServlet(servicePort);
            if (returnValue.equals("pid"))
            {
                retval=Integer.toString(pid);
            }
        }
        out.println("GOOD");
        out.println(retval);
    }

    private String commaJoinedOrNull(String fieldName, Set<String> set)
    {
        if (set==null)
        {
            return null;
        }

        Iterator<String> i=set.iterator();
        StringBuilder sb=new StringBuilder();
        sb.append(i.next());

        while (i.hasNext())
        {
            sb.append(',');
            sb.append(i.next());
        }

        return sb.toString();
    }

    private String theOneRequired(String fieldName, Set<String> set)
    {
        if (set==null)
        {
            throw new IllegalArgumentException(fieldName+" required, but not provided");
        }
        Iterator<String> i=set.iterator();
        String value=i.next();
        if (i.hasNext())
        {
            throw new IllegalArgumentException("precisely one "+fieldName+" required, but "+set.size()+" provided");
        }
        return value;
    }

    private String oneOrNull(String fieldName, Set<String> set)
    {
        if (set==null)
        {
            return null;
        }
        Iterator<String> i=set.iterator();
        String value=i.next();
        if (i.hasNext())
        {
            throw new IllegalArgumentException("precisely one "+fieldName+" required, but "+set.size()+" provided");
        }
        return value;
    }


    private
    void tagPresentDate(Properties p, ServletProps key)
    {
        String value=iso_8601_ish.format(new Date());
        p.setProperty(key.toString(), value);
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

    private
    Filter getFilter(List<String> args) throws IOException
    {
        Filter root=new Filter();
        Filter building=root;

        Iterator<String> i=args.iterator();

        while (i.hasNext())
        {
            String flag=i.next();
            String argument=null;

            {
                int equals=flag.indexOf('=');
                if (equals > 0)
                {
                    argument=flag.substring(equals+1);
                    flag=flag.substring(0, equals);
                    //log.println("split: flag="+flag+" & arg="+argument);
                }
            }

            //most trailing "s"es can be ignored (except: "unless")
            if (flag.endsWith("s") && !flag.endsWith("unless"))
            {
                flag=flag.substring(0, flag.length()-1);
                log.println("trimming 's' from flag yields: "+flag);
            }

            if (flag.endsWith("all"))
            {
                building.explicitMatchAll=true;
                continue;
            }

            if (flag.endsWith("or"))
            {
                building=building.or();
                continue;
            }

            if (flag.endsWith("except") || flag.endsWith("and-not") || flag.endsWith("unless"))
            {
                building=building.andNot();
                continue;
            }

            if (flag.endsWith("all-but"))
            {
                building.explicitMatchAll=true;
                building=building.andNot();
                continue;
            }

            if (flag.endsWith("where"))
            {
                building=root.where();
                continue;
            }

            //------------
            // A hack to accept entries like --not-port 123 & --except-name bob
            Filter filter=building;

            if (flag.contains("not-") || flag.contains("except-"))
            {
                filter=filter.andNot();
            }

            //------------

            if (argument==null)
            {
                try {
                    argument=i.next();
                } catch (NoSuchElementException e) {
                    throw new IllegalArgumentException(flag+" requires one argument", e);
                }
            }

            if (flag.endsWith("heap"))
            {
                filter.heap(argument);
            }
            else if (flag.endsWith("jmx") || flag.endsWith("jmx-port"))
            {
                filter.jmx(argument);
            }
            else if (flag.endsWith("port"))
            {
                filter.port(argument);
            }
            else if (flag.endsWith("name"))
            {
                filter.name(argument);
            }
            else if (flag.endsWith("path"))
            {
                filter.path(argument);
            }
            else if (flag.endsWith("perm"))
            {
                filter.perm(argument);
            }
            else if (flag.endsWith("pid"))
            {
                filter.pid(argument);
            }
            else if (flag.contains("stack"))
            {
                filter.stack(argument);
            }
            else if (flag.endsWith("tag"))
            {
                filter.tag(argument);
            }
            else if (flag.endsWith("version"))
            {
                filter.version(argument);
            }
            else if (flag.contains("war"))
            {
                filter.war(argument);
            }
            else if (flag.contains("with-"))
            {
                String optionName=flagToOptionName(flag);
                log.println("* option: "+optionName+" = "+argument);
                filter.option(optionName, argument);
            }
            else if (flag.endsWith("with"))
            {
                String[] options=argument.split(",");
                for (String optionName : options) {
                    String value="TRUE";
                    log.println("* option: "+optionName+" = "+value);
                    filter.option(optionName, value);
                }
            }
            else
            {
                throw new IllegalArgumentException("Unknown command line argument/flag: "+flag);
            }
        }

        //We must straighten out the logical backwards-ness... "where" is the primary filter, if it exists
        if (root.whereFilter!=null)
        {
            Filter primary=root.whereFilter;
            Filter setFilter=root;
            root.whereFilter=null;
            primary.kludgeSetFilter=setFilter;
            return primary;
        }
        else
        {
            return root;
        }

    }

    /**
     * given "--with-host" or "-with-host" return "host"
     */
    private
    String flagToOptionName(String flag)
    {
        int hypen=flag.indexOf("-", 3);
        if (hypen<0)
        {
            throw new IllegalArgumentException("does not look like an option flag: "+flag);
        }
        return flag.substring(hypen+1);
    }

    private
    void doStatsCommand(Filter filter, PrintStream out) throws IOException
    {
        List<Properties> matchingProperties = propertiesFromMatchingConfigFiles(filter);

        out.println("GOOD");

        boolean anyVersions=anyPropertyHas(matchingProperties, VERSION);
        boolean anyTags    =anyPropertyHas(matchingProperties, TAGS);

        if (true)
        { //scope for A & B

            StringBuilder a=new StringBuilder();
            StringBuilder b=new StringBuilder();

            //Basically... don't print those columns that are already specified by filter...
            // and print the predictably-sized fields first, then likely-to-be-small, then rest
            // PORT | LIFE | HEAP | PERM | VERSION | PATH | App-name

            if (filter.port==null)
            {
                a.append(" Port  |");
                b.append("-------+");
                //append(" 10000 |");
            }

            a.append("  PID  | Life |  Heap Usage   | PermGen Usage ");
            b.append("-------+------+---------------+---------------");
            //append(" 12345 | LIVE |  100% of 999m |  100% of 999m ");
            //append(" 12333 | DEAD |   10% of   3g |   10% of   9m ");
            //append("    -1 | STOP |    - N/A -    |    - N/A -    ");

            if (filter.version==null && anyVersions)
            {
                a.append("| Version ");
                b.append("+---------");
                //append("| v0.3.31 ");
                //append("|   N/A   ");
            }

            if (filter.path==null)
            {
                a.append("| Request Path ");
                b.append("+--------------");
                //append("| /latest      ");
                //append("| /wui         ");
                //append("| /statements  ");
            }

            if (filter.tag==null && anyTags)
            {
                a.append("|     Tags    ");
                b.append("+-------------");
                //append("| production  ");
                //append("| testing     ");
                //append("| development ");
                //append("| integration ");
            }

            if (filter.name==null)
            {
                a.append("| Application Name");
                b.append("+----------------------");
                //append("| capillary-wui\n");
                //append("| android-distribution\n");
                //append("| cantina-web\n");
            }

            out.println(a.toString());
            out.println(b.toString());

        } //scope for A & B

        int count=0;

        StringBuilder line=new StringBuilder(200);

        for (Properties p : matchingProperties)
        {
            count++;

            if (filter.port==null)
            {
                //append(" Port  |");
                //append("-------+");
                //append(" 10000 |");
                line.append(' ');
                line.append(String.format("%5s", p.getProperty(SERVICE_PORT.toString(), "Err")));
                line.append(" |");
            }

            //append("  PID  | Life |  Heap Usage   | PermGen Usage ");
            //append("-------+------+---------------+---------------");
            //append(" 12345 | LIVE |  100% of 999m |  100% of 999m ");
            //append(" 12333 | DEAD |   10% of   3g |   10% of   9m ");
            //append("    -1 | STOP |    - N/A -    |    - N/A -    ");
            //append(" 12222 | LIVE |    No JMX     |    No JMX     ");
            int pid=pid(p);

            line.append(' ');
            line.append(String.format("%5d", pid));

            ServletMemoryUsage smu=null;

            if (pid<=1)
            {
                String heap=p.getProperty(HEAP_SIZE.toString(), "N/A");
                String perm=p.getProperty(PERM_SIZE.toString(), "N/A");

                line.append(" | STOP |  ");
                line.append(String.format("%12s", heap));
                line.append(" |  ");
                line.append(String.format("%12s", perm));
                line.append(" ");
            }
            else if (isRunning(pid))
            {
                String jmxString=p.getProperty(JMX_PORT.toString());

                if (jmxString!=null) {
                    try {
                        int jmxPort=Integer.parseInt(jmxString);
                        smu=JMXUtils.getMemoryUsageGivenJMXPort(jmxPort);
                    } catch (Throwable t) {
                        t.printStackTrace();
                    }
                }
                if (smu==null)
                {
                    line.append(" | LIVE |    No JMX     |    No JMX     ");
                }
                else
                {
                    line.append(" | LIVE |  ");
                    line.append(smu.getHeapSummary());
                    line.append(" |  ");
                    line.append(smu.getPermGenSummary());
                    line.append(' ');
                }
            } else {
                String heap=p.getProperty(HEAP_SIZE.toString(), "N/A");
                String perm=p.getProperty(PERM_SIZE.toString(), "N/A");

                line.append(" | DEAD |  ");
                line.append(String.format("%12s", heap));
                line.append(" |  ");
                line.append(String.format("%12s", perm));
                line.append(" ");
            }

            if (filter.version==null && anyVersions)
            {
                //append("| Version ");
                //append("+---------");
                //append("| v0.3.31 ");
                //append("|   N/A   ");
                line.append("| ");
                line.append(String.format("%-7s", p.getProperty(VERSION.toString(), "")));
                line.append(' ');
            }

            if (filter.path==null)
            {
                //append("| Request Path ");
                //append("+--------------");
                //append("| /latest      ");
                //append("| /wui         ");
                //append("| /statements  ");
                line.append("| ");
                line.append(String.format("%-12s", p.getProperty(PATH.toString())));
                line.append(" ");
            }

            if (filter.tag==null && anyTags)
            {
                //append("|     Tag     ");
                //append("+-------------");
                //append("| production  ");
                //append("| testing     ");
                //append("| development ");
                //append("| integration ");
                line.append("| ");
                line.append(String.format("%-11s", p.getProperty(TAGS.toString(), "")));
                line.append(' ');
            }

            if (filter.name==null)
            {
                //append("| Application Name");
                //append("+----------------------");
                //append("| capillary-wui\n");
                //append("| android-distribution\n");
                //append("| cantina-web\n");
                line.append("| ");
                line.append(p.getProperty(NAME.toString(), "N/A"));
            }

            out.println(line.toString());
            line.setLength(0);
        }

        out.println();

        String message="stats matched "+count+" servlets";
        out.println(message);
        log.println(message);
    }

    private
    boolean anyPropertyHas(List<Properties> matchingProperties, ServletProps key)
    {
        String keyString=key.toString();

        for (Properties property : matchingProperties)
        {
            if (property.containsKey(keyString))
            {
                return true;
            }
        }

        return false;
    }

    private
    List<Properties> propertiesFromMatchingConfigFiles(Filter filter) throws IOException
    {
        List<Properties> retval=new ArrayList<Properties>();

        for (File file : etcDirectory.listFiles())
        {
            if (!file.getName().endsWith(".config"))
            {
                continue;
            }
            Properties p=propertiesFromFile(file);
            if (filter.matches(p))
            {
                retval.add(p);
            }
        }
        return retval;
    }

    private
    List<Properties> propertiesFromAllConfigFiles() throws IOException
    {
        List<Properties> retval=new ArrayList<Properties>();

        for (File file : etcDirectory.listFiles())
        {
            if (!file.getName().endsWith(".config"))
            {
                continue;
            }
            retval.add(propertiesFromFile(file));
        }
        return retval;
    }

}
