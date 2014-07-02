package com.allogy.infra.hyperjetty.server;

import com.allogy.infra.hyperjetty.common.Config;
import com.allogy.infra.hyperjetty.common.ProcessUtils;
import com.allogy.infra.hyperjetty.common.ServletProp;

import javax.management.*;
import javax.xml.bind.DatatypeConverter;
import java.io.*;
import java.net.*;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

import static com.allogy.infra.hyperjetty.common.ServletProp.CONTEXT_PATH;
import static com.allogy.infra.hyperjetty.common.ServletProp.DATE_CREATED;
import static com.allogy.infra.hyperjetty.common.ServletProp.DATE_RESPAWNED;
import static com.allogy.infra.hyperjetty.common.ServletProp.DATE_STARTED;
import static com.allogy.infra.hyperjetty.common.ServletProp.DEPLOY_DIR;
import static com.allogy.infra.hyperjetty.common.ServletProp.HEAP_SIZE;
import static com.allogy.infra.hyperjetty.common.ServletProp.JMX_PORT;
import static com.allogy.infra.hyperjetty.common.ServletProp.LOG_BASE;
import static com.allogy.infra.hyperjetty.common.ServletProp.NAME;
import static com.allogy.infra.hyperjetty.common.ServletProp.OPTIONS;
import static com.allogy.infra.hyperjetty.common.ServletProp.ORIGINAL_WAR;
import static com.allogy.infra.hyperjetty.common.ServletProp.PATH;
import static com.allogy.infra.hyperjetty.common.ServletProp.PERM_SIZE;
import static com.allogy.infra.hyperjetty.common.ServletProp.PID;
import static com.allogy.infra.hyperjetty.common.ServletProp.PORT_NUMBER_IN_LOG_FILENAME;
import static com.allogy.infra.hyperjetty.common.ServletProp.RESPAWN_COUNT;
import static com.allogy.infra.hyperjetty.common.ServletProp.SERVICE_PORT;
import static com.allogy.infra.hyperjetty.common.ServletProp.STACK_SIZE;
import static com.allogy.infra.hyperjetty.common.ServletProp.TAGS;
import static com.allogy.infra.hyperjetty.common.ServletProp.VERSION;
import static com.allogy.infra.hyperjetty.common.ServletProp.WITHOUT;

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

    /**
     * The amount of time (measured in milliseconds) which the service should "sleep" between
     * connections. Approximately equates to "time between checking for dead services".
     */
    private static final int PERIODIC_SLEEP_MS = 5000;

    /**
     * The amount of time (measured in milliseconds) less than which the service should
     * absolutely not restart a dead service (the service won't even check). If an respawning
     * service fails instantly on startup, this will be the frequency at which it will be
     * revived (*possibly* to work again). On the other hand, if a "mostly stable" servlet
     * (that has been running a while) dies, it will be restarted almost instantly as governed
     * by PERIODIC_SLEEP_MS).
     */
    private static final long MAX_RESTART_THRASHING_PERIOD_MS = 60000;

    private static final String JENKINS_USERNAME="hyperjetty";
    private static final String JENKINS_PASSWORD="QeFNTVz6X2QCNQUdCna7CNg";
    private static final String JENKINS_API_TOKEN="953bc272e52f7dc3b75491cfd18e5771";

    private static final String JENKINS_AUTHORIZATION_HEADER;

    static
    {
        //String authString=JENKINS_USERNAME+":"+JENKINS_PASSWORD;
        String authString=JENKINS_USERNAME+":"+JENKINS_API_TOKEN;
        JENKINS_AUTHORIZATION_HEADER="Basic "+nodeps_jdk6_base64Encode(authString);
    }

    private final File libDirectory;
    private final File etcDirectory;
    private final File logDirectory;
    private final File webappDirectory;
    private final ServerSocket serverSocket;
    private final PrintStream log;

    private static final String DISABLED = ".DISABLED";

    private File jettyRunnerJar=new File("lib/jetty-runner.jar");
    private File hjWebappJar   =new File("hyperjetty-webapp/target/hyperjetty-webapp.jar");
    private File hjRuntimeJar   =new File("hyperjetty-runtime/target/hyperjetty-runtime.jar");
    private File jettyJmxJar   =new File("lib/jetty-jmx.jar"+DISABLED);
    private File jettyJmxXml   =new File("etc/jetty-jmx.xml"+DISABLED);

    private static final File heapDumpPath=new File("/var/lib/hyperjetty");

    public
    Service(int controlPort, File webappDirectory, File libDirectory, File etcDirectory, File logDirectory, String jettyRunnerJar) throws IOException
    {
        log=System.out;

        this.serverSocket = new ServerSocket(controlPort);
        this.libDirectory = libDirectory;
        this.etcDirectory = etcDirectory;
        this.logDirectory = logDirectory;
        this.webappDirectory = webappDirectory;

        if (jettyRunnerJar!=null)
        {
            this.jettyRunnerJar = new File(jettyRunnerJar);
        }

        if (!hjWebappJar.canRead())
        {
            this.hjWebappJar = new File(libDirectory, hjWebappJar.getName());
            this.hjRuntimeJar= new File(libDirectory, hjRuntimeJar.getName());
        }

        if (!webappDirectory.canWrite())
        {
            log.println("WARNING: webapp directory is not writable: "+webappDirectory);
        }

        if (!etcDirectory.canWrite())
        {
            log.println("WARNING: etc directory is not writable: "+etcDirectory);
        }

        assertReadableDirectory(libDirectory);
        assertReadableDirectory(webappDirectory);
        assertReadableDirectory(etcDirectory);
        assertWritableDirectory(logDirectory);

        mustBeReadableFile(this.jettyRunnerJar);

        serverSocket.setSoTimeout(PERIODIC_SLEEP_MS);
    }

    public int minimumServicePort =10000;
    public int minimumJMXPort     =11000;

    private
    void assertReadableDirectory(File directory)
    {
        if (!directory.isDirectory() || !directory.canRead())
        {
            throw new IllegalArgumentException("not a readable directory: "+directory);
        }
    }

    private
    void assertWritableDirectory(File directory)
    {
        if (!directory.isDirectory() || !directory.canWrite())
        {
            throw new IllegalArgumentException("not a writable directory: "+directory);
        }
    }

    private static
    String systemPropertyOrEnvironment(String key, String _default)
    {
        return Config.systemPropertyOrEnvironment(key, _default);
    }

    public static
    void main(String[] args)
    {
        String controlPort    = systemPropertyOrEnvironment("CONTROL_PORT"    , null);
        String libDirectory   = systemPropertyOrEnvironment("LIB_DIRECTORY"   , null);
        String webappDirectory= systemPropertyOrEnvironment("WEBAPP_DIRECTORY", null);
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
                else if (flag.contains("-app"))
                {
                    webappDirectory=argument;
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
            String usage="\n\nusage: "+NAME+" --apps /var/lib/"+NAME+" --lib /usr/share/java --log /var/log/"+NAME+" --port 1234 --jetty-runner-jar /usr/libexec/"+NAME+"/jetty-runner.jar";
            if (controlPort   ==null) die("controlPort is unspecified"   +usage);
            if (logDirectory  ==null) die("logDirectory is unspecified"  +usage);
            if (libDirectory  ==null) die("libDirectory is unspecified"  +usage);
            if (etcDirectory  ==null) die("etcDirectory is unspecified"  +usage);
            if (webappDirectory==null) die("webappDirectory is unspecified"+usage);
            // (jettyRunnerJar==null) die("jettyRunnerJar is unspecified"+usage);
        }

        System.err.print("Control Port : "); System.err.println(controlPort );
        System.err.print("Webapp Directory: "); System.err.println(webappDirectory);
        System.err.print("Lib Directory: "); System.err.println(libDirectory);
        System.err.print("Log Directory: "); System.err.println(logDirectory);
        System.err.print("Jetty Runner : "); System.err.println(jettyRunnerJar);

        try {
            Service service=new Service(
                    Integer.parseInt(controlPort),
                    new File(webappDirectory),
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

    private static
    void die(String s)
    {
        System.err.println(s);
        System.exit(1);
    }

    private boolean alive=true;

    @Override
    public void run()
    {
        provisionallyLaunchAllPreviousServlets();

        log.println("HyperJetty on "+getHostname("unknown host")+" is ready to receive connections...");

        while (alive)
        {
            /*
             * NB: We process all connections ONE-AT-A-TIME, otherwise we would have to do much locking & concurrency control
             */
            Socket socket=null;
            try {
                socket=serverSocket.accept();
                processClientSocketCommand(socket.getInputStream(), socket.getOutputStream());
            } catch (SocketTimeoutException e) {
                socket=null;
                try {
                    doPeriodicTasks();
                } catch (Exception e2) {
                    e2.printStackTrace();
                }
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

    private
    void doPeriodicTasks()
    {
        //log.println("doPeriodicTasks()");
        for (File file : etcDirectory.listFiles())
        {
            if (!file.getName().endsWith(".config"))
            {
                continue;
            }
            try {
                Properties p=propertiesFromFile(file);
                Date lastRespawn=getDate(p, DATE_RESPAWNED);
                boolean thrashingIfDead=(lastRespawn!=null && isWithinRespawnThrashingDelay(lastRespawn));

                int pid = pid(p);

                if (pid>0)
                {
                    Boolean running= ProcessUtils.processState(pid, false);

                    if (running!=null && !running)
                    {
                        if (thrashingIfDead)
                        {
                            log.println("(!) thrashing: "+file);
                        }
                        else
                        {
                            Date noticed=logDate();
                            log.println("respawning dead process (pid="+pid+"): "+file);
                            doRespawn(p, noticed);
                        }
                    }
                }

            } catch (Exception e) {
                System.err.println("While processing: "+file);
                e.printStackTrace();
            }

        }
    }

    private
    void doRespawn(Properties p, Date noticed) throws IOException
    {
        tagPresentDate(p, DATE_RESPAWNED);
        int count=Integer.parseInt(p.getProperty(RESPAWN_COUNT.toString(), "0"));
        count++;
        p.setProperty(RESPAWN_COUNT.toString(), Integer.toString(count));
        int servicePort= Integer.parseInt(p.getProperty(SERVICE_PORT.toString()));
        int oldPid=pid(p);

        writeProperties(p, configFileForServicePort(servicePort));
        actuallyLaunchServlet(servicePort);

        maybeNoticeAndReportHeapDump(servicePort, p, noticed, oldPid);
    }

    private
    void maybeNoticeAndReportHeapDump(int servicePort, Properties p, Date noticed, int oldPid)
    {
        File heapDump=new File(heapDumpPath, "java_pid"+oldPid+".hprof");
        if (heapDump.canRead())
        {
            String name=p.getProperty(NAME.toString());
            String version=p.getProperty(VERSION.toString());
            log.println("(!): OOM  / "+name+" @ "+version+": "+heapDump);

            String dupeSuppressKey="OOM_"+version;
            if (p.containsKey(dupeSuppressKey))
            {
                log.println("suppressing duplicate OOM report");
                return;
            }

            String dupeSuppressValue=iso_8601_ish.format(new Date());
            p.setProperty(dupeSuppressKey, dupeSuppressValue);

            File redmine_ticket=new File("/builder/redmine_ticket.sh");
            if (redmine_ticket.canExecute())
            {
                log.println("reporting OOM via: "+redmine_ticket);
                String summary=name+" has run out of memory";

                File descriptionFile=null;
                //File configFile = configFileForServicePort(servicePort);
                try {
                    descriptionFile=generateOOMReportDescriptionFile(servicePort, p, oldPid, heapDump);

                    Runtime.getRuntime().exec(new String[]{
                        redmine_ticket.getAbsolutePath(),
                        "--summary"    , summary,
                        "--description", descriptionFile.getAbsolutePath(),
                      /*"--attachment" , heapDump.getAbsolutePath()    <-- hundreds of megabytes, too big for redmine attachment */
                    });
                } catch (Throwable t) {
                    t.printStackTrace();
                } finally {
                    //NB: if we delete the file, the redmine_ticket process won't get it...
                    //descriptionFile.delete();
                }
            }
            else
            {
                //TODO: issue a syslog event!
                log.println("no supported method to report OOM");
            }
        }
        else
        {
            log.println("dne: "+heapDump);
        }
    }

    public static
    String getHostname(String _default)
    {
        String hostname=null;

        try
        {
            java.net.InetAddress addr = java.net.InetAddress.getLocalHost();

            /* Get IP Address? Is this needed?
            byte[] ipAddr = addr.getAddress();
            */

            // Get hostname
            hostname = addr.getHostName();

        }
        catch (Exception e)
        {
            System.out.println("Get hostname by localhost socket failed: "+e);
        }

        if (hostname==null || hostname.length()==0)
        {
            return _default;
        }
        else
        {
            return hostname;
        }
    }

    private
    File generateOOMReportDescriptionFile(int servicePort, Properties p, int oldPid, File heapDump) throws IOException
    {
        File retval=File.createTempFile("hj-oom-report-",".txt");

        String hostname=getHostname(null);

        StringBuilder sb=new StringBuilder();

        sb.append("\nA hyperjetty-managed java servlet has encountered an out-of-memory condition (OOM).\n");
        sb.append("\nPID  = ");
        sb.append(oldPid);
        if (hostname!=null)
        {
            sb.append("\nHOST = ");
            sb.append(hostname);
        }
        sb.append("\nTIME = ");
        sb.append(iso_8601_ish.format(new Date()));
        sb.append(" (that hj noticed)\n\n");

        if (hostname!=null)
        {
            sb.append("The heap dump (which can be hundreds of megabytes), can be obtained by executing a command such as:\n<pre>\n");
            sb.append("scp ").append(hostname).append(":").append(heapDump.getAbsolutePath()).append(" /tmp/\n");
            sb.append("</pre>\n\n");
        }

        FileOutputStream out=new FileOutputStream(retval);

        out.write(sb.toString().getBytes());
        p.store(out, " -- Servlet Properties Follow -- #");
        out.flush();
        out.close();

        return retval;
    }

    private
    boolean isWithinRespawnThrashingDelay(Date lastRespawn)
    {
        long now=System.currentTimeMillis();
        long then=lastRespawn.getTime();
        long millis=(now-then);
        return (millis<MAX_RESTART_THRASHING_PERIOD_MS);
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
            e.printStackTrace();
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

    /**
     * The war file & sibling directory *must* be in the same directory for jetty to notice.
     * Otherwise it will be deployed to /tmp.
     * @param servicePort
     * @return
     */
    private
    File warFileForServicePort(int servicePort)
    {
        return new File(webappDirectory, servicePort+".war");
    }

    /**
     * The war file & sibling directory *must* be in the same directory for jetty to notice.
     * Otherwise it will be deployed to /tmp.
     * @param servicePort
     * @return
     */
    private
    File siblingDirectoryForServicePort(int servicePort)
    {
        return new File(webappDirectory, Integer.toString(servicePort));
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

        File siblingDirectory;

        if (p.containsKey(DEPLOY_DIR.toString()))
        {
            siblingDirectory = new File(p.getProperty(DEPLOY_DIR.toString()));
        }
        else
        {
            siblingDirectory = siblingDirectoryForServicePort(servicePort);
        }

        createMagicSiblingDirectoryForJetty(warFile, siblingDirectory);

        //NB: by this point, we really need to have NAME set, or else this will fixate 'no-name' within the log base.

        String logFileBase= logFileBaseFromProperties(p, true);

        String logFile=logFileBase+".log";
        String accessLog=logFileBase+".access";

        log.println("LOG="+logFile);
        log.println("ACCESS_LOG="+accessLog);

        LaunchOptions launchOptions=new LaunchOptions(libDirectory, webappDirectory);

        {
            String without=p.getProperty(WITHOUT.toString());
            if (without!=null)
            {
                String[] optionNames=without.split(",");

                for (String optionName : optionNames)
                {
                    log.println("without: "+optionName);
                    launchOptions.blacklist(optionName);
                }
            }
        }

        launchOptions.enable("default");
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

        for (Object o : p.keySet())
        {
            final String key=(String)o;

            if (key.toLowerCase().startsWith("java_"))
            {
                final String value=p.getProperty(key);
                final String defineName=LaunchOptions.stripUnderscoredPrefix(key);


                log.println("java system define: "+defineName+"="+value);

                launchOptions.addJavaDefine(defineName, value);
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

        // See #4342 for reasoning, heap debugger does not like the default OpenJDK Garbage Collector (?!?!)
        sb.append(" -XX:+UseG1GC"); // see: http://www.oracle.com/webfolder/technetwork/tutorials/obe/java/G1GettingStarted/index.html
        sb.append(" -XX:+PrintGC"); // print gc pause durations along with what "type" they were
        sb.append(" -XX:+HeapDumpOnOutOfMemoryError");
        //There are seemingly some JVMS that do not give time for the heap dump to be generated (i.e. need to add sleep before kill), but at least ours seems to be okay.
        sb.append(" -XX:OnOutOfMemoryError=\"kill -9 %p\"");

        if (heapDumpPath.isDirectory())
        {
            sb.append(" -XX:HeapDumpPath=").append(heapDumpPath.toString());
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

        boolean substituteAccessLog=launchOptions.hasJavaDefine("HJ_ACCESS_LOG_REPLACEMENT=true");

        if (substituteAccessLog)
        {
            sb.append(" -DHJ_ACCESS_LOG=").append(accessLog);
        }

        sb.append(" -Dvisualvm.display.name=").append(debugProcessNameWithoutSpaces(p));

        sb.append(launchOptions.getJavaDefines());

        if (jettyJmxJar!=null && jettyJmxJar.exists())
        {
            launchOptions.addJar(jettyJmxJar);
        }

        boolean hasJUnixSockets=false;
        File jUnixSockets=new File(jettyRunnerJar.getParentFile(), "junixsocket.jar");

        if (jUnixSockets.exists())
        {
            hasJUnixSockets=true;
            launchOptions.addJar(jUnixSockets);
        }

        if (hjWebappJar.exists())
        {
            launchOptions.addJar(hjWebappJar);
        }
        else
        {
            log.println("dne: "+hjWebappJar);
        }

        if (hjRuntimeJar.exists())
        {
            launchOptions.addJar(hjRuntimeJar);
        }
        else
        {
            log.println("dne: "+hjRuntimeJar);
        }

        //Add the jetty-runner.jar last, so that we can override it's classes as needed
        launchOptions.addJar(jettyRunnerJar);

        sb.append(" -cp ");

        launchOptions.appendClassPath(sb);

        sb.append(" com.allogy.infra.hyperjetty.runtime.Runner");

        /*
        if (JETTY_VERSION > 8)
        {
            sb.append(" org.eclipse.jetty.runner.Runner");
        }
        else
        {
            sb.append(" org.mortbay.jetty.runner.Runner");
        }
        */

        //NB: "--without-stats" ---yields--> isBlacklisted("stat")
        boolean hasStatsServlet=(!launchOptions.isBlacklisted("stat"));

        if (hasStatsServlet)
        {
            //starts a "/stats" servlet... probably harmless (minor overhead)
            sb.append(" --stats unsecure");
        }

        for (String jettyConfigFile : launchOptions.getJettyConfigFiles())
        {
            sb.append(" --config ").append(jettyConfigFile);
        }

        if ((arg=p.getProperty(SERVICE_PORT.toString()))!=null)
        {
            sb.append(" --port ").append(arg);
        }

        String path=getContextPath(p);

        if (path!=null)
        {
            sb.append(" --path ").append(path);

            if (!p.containsKey(CONTEXT_PATH.toString()))
            {
                p.setProperty(CONTEXT_PATH.toString(), path);
            }
        }

        if (!substituteAccessLog)
        {
            sb.append(" --log ").append(accessLog);
        }

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

        Map<String, String> env = processBuilder.environment();
        env.put("HJ_PORT"      , Integer.toString(servicePort));
        env.put("HJ_LOG"       , logFile);
        env.put("HJ_ACCESS_LOG", accessLog);
        env.put("HJ_STATS"     , (hasStatsServlet?"TRUE":"FALSE"));
        env.put("HJ_CONFIG_FILE", configFile.getAbsolutePath());

        for (ServletProp prop : Config.getPropsAvailableViaEnvironment())
        {
            String key=prop.toString();
            String value=p.getProperty(key);

            if (value!=null)
            {
                env.put("HJ_"+key, value);
            }
        }

        env.remove("LS_COLORS");
        env.remove("SSH_CLIENT");
        env.remove("SSH_CONNECTION");

        if (hasJUnixSockets)
        {
            env.put("HJ_UNIX_SOCKET", "/sock/hj/"+servicePort);
        }

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

        if (!presentAndFalse(p, PORT_NUMBER_IN_LOG_FILENAME))
        {
            String name=p.getProperty(NAME.toString());
            swapLatestLogFileLinks(new File(logFile  ), name+".latest.log"   );
            swapLatestLogFileLinks(new File(accessLog), name+".latest.access");
        }

        return pid;
    }

    /**
     * SIDE-EFFECT: jetty's WebInfConfiguration class will notice this directory and use it over a /tmp directory
     *
     * @throws IOException
     */
    private
    void createMagicSiblingDirectoryForJetty(File warFile, File siblingDirectory) throws IOException
    {
        boolean forceJettyToRedeploy;

        if (siblingDirectory.isDirectory())
        {
            log.println("is directory: "+siblingDirectory);
            forceJettyToRedeploy=false;
        }
        else
        if (siblingDirectory.mkdir())
        {
            log.println("created directory: "+siblingDirectory);
            forceJettyToRedeploy=true;
        }
        else
        {
            throw new IOException("unable to create sibling directory: "+siblingDirectory);
        }

        if (forceJettyToRedeploy)
        {
            /*
            We must either make sure the modification time of the directory (which we just created) is *older* than
            the war file (which is pre-existing), or else trick jetty into thinking it crashed while extracting it.
            * /
            File extractLock=new File(contextTempDirectory, ".extract_lock");
            extractLock.createNewFile();
            */
            siblingDirectory.setLastModified(warFile.lastModified()-1000); //some platforms have only 1-second resolution
        }
    }

    private
    void swapLatestLogFileLinks(File logFile, String genericBase)
    {
        createEmptyLogFile(logFile);
        try {
            String[] command=new String[]{
                "/bin/ln",
                "-sf",
                logFile.getName(),
                genericBase
            };
            log.println("["+logFile.getParentFile()+"] "+command[0]+" "+command[1]+" "+command[2]+" "+command[3]);
            Process process = Runtime.getRuntime().exec(command, null, logFile.getParentFile());
            if (false) {
                process.waitFor();
                int exitValue=process.exitValue();
                log.println("exit status: " + exitValue);
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    private
    void createEmptyLogFile(File logFile)
    {
        try {
            if (!logFile.exists() && !logFile.createNewFile())
            {
                log.println("ERROR: unable to create: "+logFile);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
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
        sb.append(getContextPath(p));

        int i;

        while ((i=sb.indexOf(" "))>=0)
        {
            sb.replace(i, i+1, "-");
        }

        return sb.toString();
    }

    private
    String getContextPath(Properties p)
    {
        String path=p.getProperty(CONTEXT_PATH.toString());

        if (path==null)
        {
            path=p.getProperty(PATH.toString());
        }

        if (path==null)
        {
            String originalWarFile=p.getProperty(ORIGINAL_WAR.toString());
            String basename=stripPathSuffixAndVersionNumber(originalWarFile);
            path=guessPathFromWar(basename);
            log.println("* guessed context path from warfile: " + originalWarFile + " -> " + path);
        }

        return path;
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

    /**
     * Pre-condition: must have name assigned, or else log base will have 'no-name' in it.
     *
     * @param p
     * @param forStartup
     * @return
     */
    private
    String logFileBaseFromProperties(Properties p, boolean forStartup)
    {
        if (forStartup)
        {
            //Whenever a servlet starts up, it gets a new LOG_BASE...
            String logDateBase=compact_iso_8601_ish_filename.format(new Date());
            String logDate=logDateBase;
            int N=1;
            String logBase=logBaseWithDateChunk(p, logDate);

            while (accessOrLogFileIsPresentFor(logBase))
            {
                log.println("(!) log base taken: "+logBase);
                N++;
                logDate=logDateBase+"-"+N;
                logBase=logBaseWithDateChunk(p, logDate);
            }

            log.println("using log base: "+logBase);
            p.setProperty(LOG_BASE.toString(), logBase);

            return logBase;
        }
        else
        {
            String logBase=p.getProperty(LOG_BASE.toString());

            //Legacy/migration... yes, LOG_DATE was more than the date component.
            if (logBase==null)
            {
                logBase=p.getProperty("LOG_DATE");
            }

            if (logBase==null)
            {
                log.println("old/compatibility log-base logic");
                if (presentAndFalse(p, PORT_NUMBER_IN_LOG_FILENAME))
                {
                    return logNameWithoutPid(p);
                }
                else
                {
                    return logNameWithPid(p);
                }
            }
            else
            {
                log.println("got LOG_BASE="+logBase+" (resets each servlet launch)");
                //return logBaseWithDateChunk(p, logBase);
                return logBase;
            }
        }
    }

    private
    boolean accessOrLogFileIsPresentFor(String logFileBase)
    {
        String logFile=logFileBase+".log";
        String accessLog=logFileBase+".access";
        return new File(logFile).exists() || new File(accessLog).exists();
    }

    private
    String logBaseWithDateChunk(Properties p, String dateChunk)
    {
        String appName=p.getProperty(NAME.toString(), "no-name");
        String basename=niceFileCharactersOnly(appName+"-"+dateChunk);

        return new File(logDirectory, basename).toString();
    }

    private
    String logNameWithoutPid(Properties p)
    {
        String appName=p.getProperty(NAME.toString(), "no-name");
        String basename=niceFileCharactersOnly(appName);
        return new File(logDirectory, basename).toString();
    }

    private
    String logNameWithPid(Properties p)
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
        return ProcessUtils.isRunning(pid);
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
        else if (command.equals("dump") || command.equals("props"))
        {
            dumpPropertiesOfOneMatchingServlet(getFilter(args), out);
        }
        else if (command.equals("dump2") || command.equals("trace"))
        {
            sendKillQuitSignalTo(getFilter(args), out);
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
    void sendKillQuitSignalTo(Filter filter, PrintStream out) throws IOException
    {
        int total=0;
        int success=0;

        List<String> failures=new ArrayList<String>();

        for (Properties properties : propertiesFromMatchingConfigFiles(filter))
        {
            total++;
            String name=humanReadable(properties);

            try {
                int pid=Integer.parseInt(properties.getProperty(PID.toString()));
                log.println("dump-trace: "+name+" (pid="+pid+")");

                Process process = Runtime.getRuntime().exec("kill -QUIT " + pid);

                process.waitFor();
                int status=process.exitValue();
                if (status==0)
                {
                    success++;
                }
                else
                {
                    String message="failed to send trace '"+name+"': kill exit status "+status;
                    failures.add(message);
                    log.println(message);
                }
            }
            catch (Throwable t)
            {
                t.printStackTrace();
                String message="failed to send trace '"+name+"': "+t.toString();
                failures.add(message);
                log.println(message);
            }
        }

        successOrFailureReport("traced", total, success, failures, out);
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

        String logBase = logFileBaseFromProperties(properties, false);
        out.println("LOG="+logBase+".log");
        out.println("ACCESS_LOG="+logBase+".access");
        out.println("CONFIG_FILE="+configFileForServicePort(servicePort));
        out.println("WAR_FILE="+warFileForServicePort(servicePort));

        int pid=pid(properties);
        //NB: PID is already in the printed (as it's in the file)... we only need to output generated or derived data.
        //out.println("PID="+pid);

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
        String path=getContextPath(properties);

        out.println("URL="+protocol+"://"+host+":"+servicePort+path);
    }

    private
    void dumpUniqueMultiKey(Filter filter, PrintStream out, ServletProp key) throws IOException
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
    void dumpSpecificKey(Filter filter, PrintStream out, ServletProp key) throws IOException
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
            String filename = logFileBaseFromProperties(properties, false) + suffix;
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

                File siblingDirectory;

                if (properties.containsKey(DEPLOY_DIR.toString()))
                {
                    siblingDirectory = new File(properties.getProperty(DEPLOY_DIR.toString()));
                }
                else
                {
                    siblingDirectory = siblingDirectoryForServicePort(servicePort);
                }

                if (siblingDirectory.isDirectory())
                {
                    String absolutePath=siblingDirectory.getAbsolutePath();

                    if (absolutePath.indexOf(' ')>=0 || absolutePath.indexOf("/.")>=0)
                    {
                        log.println("cowardly refusing to: rm -rf "+absolutePath);
                    }
                    else
                    {
                        Runtime.getRuntime().exec(new String[]{
                            "rm",
                            "-rf",
                            absolutePath
                        });
                    }
                }

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
        String basename=new File(properties.getProperty(ORIGINAL_WAR.toString(), "no-name")).getName();
        String name=properties.getProperty(NAME.toString(), basename);
        String port=properties.getProperty(SERVICE_PORT.toString(), "no-port");
        String path=getContextPath(properties);

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

    //NB: presentAndTrue & presentAndFalse are not complementary (they both return false for NULL or unknown)
    private
    boolean presentAndTrue(Properties p, ServletProp key)
    {
        String s=p.getProperty(key.toString());
        if (s==null || s.length()==0) return false;
        char c=s.charAt(0);
        return (c=='t' || c=='T' || c=='1' || c=='y' || c=='Y');
    }

    //NB: presentAndTrue & presentAndFalse are not complementary (they both return false for NULL or unknown)
    private
    boolean presentAndFalse(Properties p, ServletProp key)
    {
        String s=p.getProperty(key.toString());
        if (s==null || s.length()==0) return false;
        char c=s.charAt(0);
        return (c=='f' || c=='F' || c=='0' || c=='n' || c=='N');
    }

    private static final DateFormat iso_8601_ish = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS' UTC'");
    private static final DateFormat iso_8601_compat1 = new SimpleDateFormat("yyyy-MM-dd HH:mm'z'");
    private static final DateFormat compact_iso_8601_ish_filename = new SimpleDateFormat("yyyyMMdd");

    static
    {
        TimeZone timeZone=TimeZone.getTimeZone("UTC");
        Calendar calendar=Calendar.getInstance(timeZone);
        iso_8601_ish.setCalendar(calendar);
        iso_8601_compat1.setCalendar(calendar);
        compact_iso_8601_ish_filename.setCalendar(calendar);
    }

    private Date logDate()
    {
        Date now=new Date();
        log.println();
        log.println(iso_8601_ish.format(now));
        return now;
    }

    private
    void doLaunchCommand(List<String> args, ObjectInputStream in, PrintStream out, int numFiles) throws IOException
    {
        Filter filter=getFilter(args);

        /* "Mandatory" arguments */
        String war     = theOneRequired("war" , filter.war );

        /* Optional arguments */
        String name        = oneOrNull("name", filter.name);
        String path        = oneOrNull("path", filter.path);

        String tag         = commaJoinedOrNull("tag"  , filter.tag  );
        String version     = commaJoinedOrNull("version", filter.version);

        String heapMemory  = oneOrNull("heap", filter.heap);
        String permMemory  = oneOrNull("perm", filter.perm);
        String stackMemory = oneOrNull("stack", filter.stack);

        String withoutOptions = commaJoinedOrNull("without", filter.without);

        /* Launch options */
        String dry  = filter.getOption("dry", null);
        String returnValue=filter.getOption("return", "port"); //by default, we will print the port number on success

        //String portNumberInLogFilename=filter.getOption("port-based-logs", null);

        String basename=stripPathSuffixAndVersionNumber(war);

        log.println("BASE="+basename);

        boolean nameIsGuessed=false;

        if (name==null)
        {
            name=guessNameFromWar(basename);
            log.println("* guessed application name from war: "+name);
            nameIsGuessed=true;
        }

        log.println("WAR ="+war );
        log.println("NAME="+name);
        log.println("PATH="+path);

        final PortReservation initialPortReservation;
        {

            if (filter.port!=null)
            {
                String servicePortString=oneOrNull("servicePort", filter.port);

                if (filter.jmxPort!=null)
                {
                    log.println("(!) jmx & service port were both specified");
                    String jmxPort=oneOrNull("jmxPort", filter.jmxPort);
                    initialPortReservation=PortReservation.exactly(servicePortString, jmxPort);
                }
                else
                {
                    log.println("(!) service port was specified");
                    initialPortReservation=PortReservation.givenFixedServicePort(servicePortString, minimumServicePort, minimumJMXPort);
                }
            }
            else
            if (filter.jmxPort != null)
            {
                log.println("(!) jmx port was specified");
                String jmxPort=oneOrNull("jmxPort", filter.jmxPort);
                initialPortReservation=PortReservation.givenFixedJMXPort(jmxPort, minimumServicePort, minimumJMXPort);
            }
            else
            {
                initialPortReservation=PortReservation.startingAt(minimumServicePort, minimumJMXPort);
            }
        }

        final PortReservation portReservation;
        final int servicePort;
        final int jmxPort;
        {
            final int initialServicePort=initialPortReservation.getServicePort();
            int tempServicePort=initialServicePort;

            if (configFileForServicePort(initialServicePort).exists())
            {
                log.println("port "+initialServicePort+" is already allocated (config file exists)");
                if (filter.port   !=null) { readAll(in); out.println("ERROR: the specified port is already allocated: " + initialServicePort); return; }
                if (filter.jmxPort!=null) { readAll(in); out.println("ERROR: the equivalent service port (for specified JMX port) is already allocated: " + initialPortReservation.getJmxPort() + " (jmx) ---> " + initialServicePort); return; }
            }

            PortReservation previousReservation=initialPortReservation;
            PortReservation newReservation=null;

            while (configFileForServicePort(tempServicePort).exists())
            {
                log.println("WARN: port already configured: "+tempServicePort);

                newReservation=PortReservation.startingAt(minimumServicePort, minimumJMXPort);
                previousReservation.release();

                tempServicePort=newReservation.getServicePort();

                if (tempServicePort==initialServicePort)
                {
                    throw new IllegalStateException("attempting to acquire a port reservation wrapped back around to "+tempServicePort+" this instance is probably 'full'...");
                }

                previousReservation=newReservation;
            }

            if (newReservation==null)
            {
                portReservation=initialPortReservation;
            }
            else
            {
                portReservation=newReservation;
            }

            servicePort = portReservation.getServicePort();
            jmxPort     = portReservation.getJmxPort();
        }

        log.println("PORT="+servicePort);
        log.println("JMX ="+jmxPort);

        final File warFile   =warFileForServicePort(servicePort);
        final File configFile=configFileForServicePort(servicePort);

        final InputStream warSource;
        final Long bytes;
        {
            if (numFiles==0 && looksLikeURL(war))
            {
                log.println("reading war file from url (3rd-party to client/server): "+war);
                warSource=inputStreamFromPossiblyKnownSourceLikeJenkins(war);
                bytes=null;
            }
            else
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

                log.println("client supplied "+numFiles+" files (expecting 1 [or a url] for launch command)");
                return;
            }
            else
            {
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

                warSource=in;
                bytes=in.readLong();

                log.println("receiving via hj-control connection: "+originalFilename+" "+bytes+" bytes => "+warFile);
            }
        }

        //Stream at most 'bytes' (could be null, meaning 'all of them') from warSource to warFile
        {
            FileOutputStream fos=new FileOutputStream(warFile);
            try
            {
                int max=4096;
                byte[] buffer=new byte[max];

                Long bytesToGo=bytes;
                int read=max;

                while ((read=warSource.read(buffer, 0, read))>0)
                {
                    fos.write(buffer, 0, read);

                    if (bytesToGo!=null)
                    {
                        bytesToGo-=read;
                        read=(int)Math.min(bytesToGo, max);
                    }
                }

                fos.flush();

                logDate();
                log.println("finished writing: "+warFile);
            }
            finally
            {
                fos.close();
            }
        }

        Properties p=null;

        try {
            p= getEmbeddedAppProperties(warFile);
        } catch (Exception e) {
            log.println("Unable to read properties from war file");
            e.printStackTrace();
        }

        if (nameIsGuessed)
        {
            if (p.containsKey(NAME.toString()))
            {
                //the name provided in the app properties overrides any guessed name
                name=p.getProperty(NAME.toString());
                log.println("NAME="+name+" (updated from app.properties)");
            }

            p.setProperty(NAME.toString(), name);
        }
        else
        {
            //the name provided on the command line overrides any in the app properties.
            p.setProperty(NAME.toString(), name);
        }

        //At this point, we at least know our application name! This will let us derive our log base, etc.

        if (version==null)
        {
            try {
                version=MavenUtils.readVersionNumberFromWarFile(warFile);
                log.println("? version from maven: "+version);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        if (version==null)
        {
            version=guessVersionNumberFromWarName(war);
            log.println("? version from war name: "+version);
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

        /*
        if (portNumberInLogFilename!=null)
        {
            p.setProperty(PORT_NUMBER_IN_LOG_FILENAME.toString(), portNumberInLogFilename);
        }
        */

        if (tag!=null)
        {
            p.setProperty(TAGS.toString(), tag);
        }

        maybeSet(p, SERVICE_PORT, Integer.toString(servicePort));
        maybeSet(p, JMX_PORT, Integer.toString(jmxPort));
        maybeSet(p, ORIGINAL_WAR, war);

        if (name!=null && !nameIsGuessed)
        {
            p.setProperty(NAME.toString(), name);
        }

        if (withoutOptions!=null)
        {
            p.setProperty(WITHOUT.toString(), withoutOptions);
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

        initialPortReservation.release();

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

    private
    InputStream inputStreamFromPossiblyKnownSourceLikeJenkins(String url) throws IOException
    {
        final URLConnection urlConnection;
        {
            if (url.contains("jenkins.allogy.com"))
            {
                log.println("recognizing jenkins url: "+url);
                url=translateJenkinsUrlToSideChannel(url);
                //log.println("Authorization: "+JENKINS_AUTHORIZATION_HEADER);
                urlConnection=new URL(url).openConnection();
                urlConnection.setRequestProperty("Authorization", JENKINS_AUTHORIZATION_HEADER);
            }
            else
            {
                urlConnection=new URL(url).openConnection();
            }
        }

        return urlConnection.getInputStream();
    }

    /**
     * Generates a URL to nginx which bypasses jenkins security measures, which seem to unconditionally respond to
     * artifact requests with a 403 error (might be a plugin conflict?).
     *
     * @param url
     * @return
     */
    public static
    String translateJenkinsUrlToSideChannel(String url)
    {
        //url="https://jenkins.allogy.com/job/Capillary%20Content%20Editor/207/artifact/target/capillary-wui.war"
        //bits:https://jenkins.allogy.com/job                 /Capillary%20Content%20Editor/      /207/artifact/target/capillary-wui.war"
        //out="https://jenkins.allogy.com/artifact-sidechannel/Capillary%20Content%20Editor/builds/207/archive /target/capillary-wui.war"
        //(i)=   0    1          2                3                        4                [dne]   5     6        7          8
        final String[] bits=url.split("/");
        final StringBuilder sb=new StringBuilder();

        int i=0;
        for (String bit : bits)
        {
            switch(i)
            {
                case 3:
                    sb.append("/artifact-sidechannel");
                    break;

                case 6:
                    sb.append("/archive");
                    break;

                case 5:
                    sb.append("/builds");
                    //FALL-THROUGH

                default:
                    sb.append('/');
                    sb.append(bit);
            }
            i++;
        }

        //Extra forward slash!
        sb.deleteCharAt(0);
        String retval=sb.toString();

        System.err.println("Translated Jenkins URL to artifact side channel: "+retval);
        return retval;
    }

    private
    boolean looksLikeURL(String fileOrUrl)
    {
        final int i=fileOrUrl.indexOf("://");
        return ( i > 0 ) && ( i < 10 );
    }

    private
    void readAll(InputStream in) throws IOException
    {
        while (in.read()>=0);
    }

    private
    String guessVersionNumberFromWarName(String name)
    {
        int slash=name.lastIndexOf('/');
        if (slash >0) name=name.substring(slash+1);
        int period = name.lastIndexOf('.');
        if (period>0) name=name.substring(0, period);
        int hypen  = name.lastIndexOf('-');
        if (hypen >0)
        {
            String beforeHypen=name.substring(0, hypen);
            String afterHypen=name.substring(hypen+1);
            if (looksLikeVersionNumber(afterHypen))
            {
                return afterHypen;
            }
            else
            {
                return null;
            }
        }
        return null;
    }

    private
    String stripPathSuffixAndVersionNumber(String name)
    {
        int slash=name.lastIndexOf('/');
        if (slash >0) name=name.substring(slash+1);
        int period = name.lastIndexOf('.');
        if (period>0) name=name.substring(0, period);
        int hypen  = name.lastIndexOf('-');
        if (hypen >0)
        {
            String beforeHypen=name.substring(0, hypen);
            String afterHypen=name.substring(hypen+1);
            if (looksLikeVersionNumber(afterHypen))
            {
                return beforeHypen;
            }
            else
            {
                return name;
            }
        }
        return name;
    }

    private
    boolean looksLikeVersionNumber(String s)
    {
        for(int i=s.length()-1; i>=0; i--)
        {
            char c=s.charAt(i);
            if (c>='0' && c<='9') continue;
            if (c=='.') continue;
            return false;
        }
        return s.length()>0;
    }

    private
    String guessPathFromWar(String warBaseName)
    {
        int hypen  = warBaseName.lastIndexOf('-');

        if (hypen>0)
        {
            System.err.println("guessPathFromWar-1: "+warBaseName);
            return "/"+warBaseName.substring(hypen+1);
        }
        else
        {
            System.err.println("guessPathFromWar-2: "+warBaseName);
            return "/"+warBaseName;
        }
    }

    private
    String guessNameFromWar(String warBaseName)
    {
        int period=warBaseName.lastIndexOf('.');
        if (period>0)
        {
            return warBaseName.substring(0, period);
        }
        else
        {
            return warBaseName;
        }
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
    Date getDate(Properties p, ServletProp key)
    {
        String value=p.getProperty(key.toString());

        if (value!=null)
        {
            try
            {
                return iso_8601_ish.parse(value);
            }
            catch (Exception e)
            {
                try
                {
                    return iso_8601_compat1.parse(value);
                }
                catch (Exception e2)
                {
                    log.print("ERROR :no matching date parser\n\t");
                    log.print(e.toString());
                    log.print("\n\t");
                    log.print(e2.toString());
                }
            }
        }
        return null;
    }

    private
    void tagPresentDate(Properties p, ServletProp key)
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
    void maybeSet(Properties p, ServletProp keyCode, String ifNotPresent)
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
                if (flag.isEmpty()) throw new IllegalArgumentException("flag reduces to empty string");
            }

            //Trim off leading hypens
            while (flag.charAt(0)=='-')
            {
                flag=flag.substring(1);
                if (flag.isEmpty()) throw new IllegalArgumentException("flag reduces to empty string");
            }

            if (flag.equals("all"))
            {
                building.explicitMatchAll=true;
                continue;
            }

            if (flag.equals("or"))
            {
                building=building.or();
                continue;
            }

            if (flag.equals("except") || flag.equals("and-not") || flag.equals("but-not") || flag.equals("unless"))
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

            // "filter" may be change
            Filter filter=building;

            //----------- no-argument options

            if (flag.startsWith("without-") && argument==null)
            {
                String optionName=flagToOptionName(flag);
                log.println("* without: "+optionName);
                filter.without(optionName);
                continue;
            }

            //------------
            // A hack to accept entries like --not-port 123 & --except-name bob

            if (flag.startsWith("not-") || flag.startsWith("except-") || flag.startsWith("without-"))
            {
                filter=filter.andNot();
                flag=flag.substring(flag.indexOf('-')+1);
                log.println("* negated flag: "+flag);
            }

            //------------

            //!!!: this "endsWith" logic has reached the end of it's utility, we should just strip the leading hypens and be done with it.
            if (flag.equals("live"   )) { filter.state("alive"  ); continue; }
            if (flag.equals("alive"  )) { filter.state("alive"  ); continue; }
            if (flag.equals("dead"   )) { filter.state("dead"   ); continue; }
            if (flag.equals("stopped")) { filter.state("stopped"); continue; }

            if (argument==null)
            {
                try {
                    argument=i.next();
                } catch (NoSuchElementException e) {
                    throw new IllegalArgumentException(flag+" requires one argument", e);
                }
                if (argument.length()==0)
                {
                    throw new IllegalArgumentException("argument cannot be the empty string");
                }
                if (argument.charAt(0)=='-')
                {
                    throw new IllegalArgumentException("arguments & flags seem to be confused: "+argument);
                }
            }

            if (flag.startsWith("heap"))
            {
                filter.heap(argument);
            }
            else if (flag.equals("jmx") || flag.equals("jmx-port"))
            {
                filter.jmx(argument);
            }
            else if (flag.endsWith("port"))
            {
                filter.port(argument);
            }
            else if (flag.equals("name"))
            {
                filter.name(argument);
            }
            else if (flag.equals("path"))
            {
                filter.path(argument);
            }
            else if (flag.startsWith("perm"))
            {
                filter.perm(argument);
            }
            else if (flag.equals("pid"))
            {
                filter.pid(argument);
            }
            else if (flag.equals("state"))
            {
                filter.state(argument);
            }
            else if (flag.startsWith("stack"))
            {
                filter.stack(argument);
            }
            else if (flag.equals("tag"))
            {
                filter.tag(argument);
            }
            else if (flag.equals("version"))
            {
                filter.version(argument);
            }
            else if (flag.startsWith("war"))
            {
                filter.war(argument);
            }
            else if (flag.startsWith("with-"))
            {
                String optionName=flagToOptionName(flag);
                log.println("* option: "+optionName+" = "+argument);
                filter.option(optionName, argument);
            }
            else if (flag.equals("with"))
            {
                String[] options=argument.split(",");
                for (String optionName : options) {
                    String value="TRUE";
                    log.println("* option: "+optionName+" = "+value);
                    filter.option(optionName, value);
                }
            }
            else if (flag.equals("without"))
            {
                log.println("* without: "+argument);
                filter.without(argument);
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
            else if (ProcessUtils.isRunning(pid))
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
                    //line.append(" | LIVE |    No JMX     |    No JMX     ");
                    String heap=p.getProperty(HEAP_SIZE.toString(), "N/A");
                    String perm=p.getProperty(PERM_SIZE.toString(), "N/A");

                    line.append(" | LIVE |  ");
                    line.append(String.format("???? of %4s", heap));
                    line.append(" |  ");
                    line.append(String.format("???? of %4s", perm));
                    line.append(" ");
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
                line.append(String.format("%-12s", getContextPath(p)));
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
    boolean anyPropertyHas(List<Properties> matchingProperties, ServletProp key)
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

        ServletStateChecker servletStateChecker=new PropFileServletChecker();

        File[] files=etcDirectory.listFiles();
        if (files==null) return retval;
        Arrays.sort(files);

        for (File file : files)
        {
            if (!file.getName().endsWith(".config"))
            {
                continue;
            }
            Properties p=propertiesFromFile(file);
            if (filter.matches(p, servletStateChecker))
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

        File[] files=etcDirectory.listFiles();
        if (files==null) return retval;
        Arrays.sort(files);

        for (File file : files)
        {
            if (!file.getName().endsWith(".config"))
            {
                continue;
            }
            retval.add(propertiesFromFile(file));
        }
        return retval;
    }

    private
    class PropFileServletChecker implements ServletStateChecker
    {

        @Override
        public ServletState getServletState(Properties p)
        {
            int pid=pid(p);
            if (pid<=0)
            {
                return ServletState.STOP;
            }
            if (ProcessUtils.isRunning(pid))
            {
                return ServletState.LIVE;
            }
            else
            {
                return ServletState.DEAD;
            }
        }
    }

    private static
    String nodeps_jdk6_base64Encode(String input)
    {
        return DatatypeConverter.printBase64Binary(input.getBytes());
    }

}
