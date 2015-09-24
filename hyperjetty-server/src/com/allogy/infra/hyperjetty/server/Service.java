package com.allogy.infra.hyperjetty.server;

import com.allogy.infra.hyperjetty.common.Config;
import com.allogy.infra.hyperjetty.common.ProcessUtils;
import com.allogy.infra.hyperjetty.common.ServletName;
import com.allogy.infra.hyperjetty.common.ServletProp;
import com.allogy.infra.hyperjetty.server.commands.DumpLogFileErrors;
import com.allogy.infra.hyperjetty.server.commands.DumpLogFileNames;
import com.allogy.infra.hyperjetty.server.commands.DumpProperties;
import com.allogy.infra.hyperjetty.server.commands.DumpServletStatus;
import com.allogy.infra.hyperjetty.server.commands.DumpSpecificKey;
import com.allogy.infra.hyperjetty.server.commands.LaunchServlet;
import com.allogy.infra.hyperjetty.server.commands.NginxRoutingTable;
import com.allogy.infra.hyperjetty.server.commands.PrintAvailableCommands;
import com.allogy.infra.hyperjetty.server.commands.StackTrace;
import com.allogy.infra.hyperjetty.server.internal.JMXUtils;

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
public
class Service implements Runnable, CommandUtilities
{

    private static final String SERVLET_STOPPED_PID = "-1";

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
		final
        File original=new File(heapDumpPath, "java_pid"+oldPid+".hprof");

        if (original.canRead())
        {
            String name=p.getProperty(NAME.toString());
            String version=p.getProperty(VERSION.toString());

			//Sweeten the name with some info we have...
			final
			File heapDump;
			{
				final
				File newPath=new File(heapDumpPath, name+version+"-pid"+oldPid+".hprof");

				if (original.renameTo(newPath))
				{
					heapDump=newPath;
				}
				else
				{
					log.println("cannot: rename "+original+" -> "+newPath);
					heapDump=original;
				}
			}

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
            log.println("dne: "+original);
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

    public
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
    public
    File warFileForServicePort(int servicePort)
    {
		final
		File exploded=new File(webappDirectory, servicePort+".dir");

		final
        File warFile=new File(webappDirectory, servicePort+".war");

		if (exploded.isDirectory())
		{
			if (warFile.exists())
			{
				log.println("WARN: preferring directory: " + exploded);
			}

			return exploded;
		}
		else
		{
			if (!warFile.exists())
			{
				log.println("WARN: warFile does not exist: "+warFile);
			}

			return warFile;
		}
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
    public
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

		if (!warFile.isDirectory())
		{
			createMagicSiblingDirectoryForJetty(warFile, siblingDirectory);
		}

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

    public
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
    public
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
        String basename=niceFileCharactersOnly(appName + "-" + portNumber);

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

    public
    boolean isRunning(Properties properties)
    {
        int pid=pid(properties);
        return ProcessUtils.isRunning(pid);
    }

    public
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

	private static final
	Map<String, Command> primaryCommands = new HashMap<String, Command>();

	private static final
	Map<String, Command> secondaryCommands = new HashMap<String, Command>();

	static
	{
		Command c;

		c=new DumpLogFileNames(true);
		{
			primaryCommands.put("access", c);
			secondaryCommands.put("access-log", c);
			secondaryCommands.put("accesslog", c);
		}

		c=new DumpLogFileNames(false);
		{
			primaryCommands.put("log", c);
			secondaryCommands.put("app-log", c);
			secondaryCommands.put("applog", c);
		}

		c=new DumpProperties();
		{
			primaryCommands.put("props", c);
			secondaryCommands.put("dump", c);
		}

		c=new StackTrace();
		{
			primaryCommands.put("stack", c);
			secondaryCommands.put("dump2", c);
			secondaryCommands.put("trace", c);
			secondaryCommands.put("stacktrace", c);
			secondaryCommands.put("stack-trace", c);
		}

		c=new DumpSpecificKey(PID);
		{
			primaryCommands.put("pid", c);
			secondaryCommands.put("pids", c);
			secondaryCommands.put("ls-pid", c);
			secondaryCommands.put("lspid", c);
		}

		c=new DumpSpecificKey(SERVICE_PORT);
		{
			primaryCommands.put("port", c);
			secondaryCommands.put("ports", c);
			secondaryCommands.put("ls-port", c);
			secondaryCommands.put("lsport", c);
		}

		c=new DumpSpecificKey(JMX_PORT);
		{
			primaryCommands.put("jmx", c);
			secondaryCommands.put("jmxports", c);
			secondaryCommands.put("jmx-port", c);
			secondaryCommands.put("lsjmx", c);
		}

		c=new DumpSpecificKey(TAGS);
		{
			primaryCommands.put("tags", c);
			secondaryCommands.put("tag", c);
			secondaryCommands.put("ls-tag", c);
			secondaryCommands.put("lstag", c);
		}

		c=new DumpSpecificKey(VERSION);
		{
			primaryCommands.put("versions", c);
			secondaryCommands.put("lsversion", c);
			secondaryCommands.put("ls-version", c);
		}

		c=new NginxRoutingTable();
		{
			primaryCommands.put("nginx", c);
		}

		c=new DumpLogFileErrors();
		{
			primaryCommands.put("errors", c);
		}

		c=new PrintAvailableCommands(primaryCommands);
		{
			primaryCommands.put("help", c);
			secondaryCommands.put("usage", c);
		}

		c=new DumpServletStatus();
		{
			primaryCommands.put("status", c);
			secondaryCommands.put("stat", c);
			secondaryCommands.put("stats", c);
			secondaryCommands.put("state", c);
			secondaryCommands.put("show", c);
		}

		c=new LaunchServlet();
		{
			primaryCommands.put("launch", c);
		}
	}

	private
	void processClientSocketCommand(InputStream inputStream, OutputStream outputStream) throws IOException
	{
		logDate();
		log.println("Got client connection");

		final PrintStream out = new PrintStream(outputStream);
		ObjectInputStream in = new ObjectInputStream(inputStream);

		int numArgs = in.readInt();
		List<String> args = new ArrayList(numArgs);

		//The first arg is the command
		String command = in.readUTF();

		log.print("* command: ");
		log.print(command);

		command=command.toLowerCase();

		for (int i = 1; i < numArgs; i++)
		{
			String arg = in.readUTF();
			args.add(arg);
			log.print(' ');
			log.print(arg);
		}
		log.println();

		int numFiles = in.readInt();

		if (numFiles > 0)
		{
			log.println(numFiles + " file(s) coming with " + command + " command");
		}

		// ------------------------------------------- CLIENT COMMANDS --------------------------------------------

		Command generic=primaryCommands.get(command);

		if (generic==null)
		{
			generic=secondaryCommands.get(command);
		}

		if (generic!=null)
		{
			final
			Filter filter=getFilter(args);

			final
			List<Properties> matching = propertiesFromMatchingConfigFiles(filter);

			generic.execute(filter, matching, this, in, out, numFiles);
		}
		else if (command.equals("restart"))
		{
			doStopCommand(getFilter(args), out, true);
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
			doStopCommand(getFilter(args), out, false);
		}
		else if (command.equals("kill"))
		{
			doKillCommand(getFilter(args), out);
		}
		else
		{
			String message = "Unknown command: " + command;
			out.println(message);
			log.println(message);
		}
		// ------------------------------------------- END CLIENT COMMANDS --------------------------------------------
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

		String keyString = key.toString();
		Set<String> values = new HashSet<String>();

		for (Properties properties : matches)
		{
			String value = properties.getProperty(keyString);
			if (value != null)
			{
				if (value.indexOf(',') >= 0)
				{
					String[] multi = value.split(",");
					for (String s : multi)
					{
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
	void doSetCommand(Filter matchingFilter, PrintStream out) throws IOException
	{
		Filter setFilter = matchingFilter.kludgeSetFilter;

		if (setFilter == null || matchingFilter.implicitlyMatchesEverything())
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

		int total = matches.size();

		if (setFilter.setCanOnlyBeAppliedToOnlyOneServlet() && total > 1)
		{
			throw new UnsupportedOperationException("requested set operation can only be applied to one servlet, but " + total + " matched");
		}

		int success = 0;
		List<String> failures = new ArrayList<String>();

		for (Properties properties : matches)
		{
			String name = humanReadable(properties);
			try
			{
				doSetCommand(properties, setFilter);
				success++;
			}
			catch (Throwable t)
			{
				t.printStackTrace();
				String message = "cant actuate set command for " + name + ": " + t.toString();
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

		if (setFilter.port != null)
		{
            /*
            Restart the world... technically an edge case (b/c we use the service port as a primary key),
            yet oddly simpler.
             */
			int oldServicePort = Integer.parseInt(oldProperties.getProperty(SERVICE_PORT.toString()));
			int newServicePort = Integer.parseInt(newProperties.getProperty(SERVICE_PORT.toString()));

			log.println("warn: changing primary port: " + oldServicePort + " -> " + newServicePort);
			if (isRunning(oldProperties))
			{
				doStopCommand(oldProperties);
				warFileForServicePort(oldServicePort).renameTo(warFileForServicePort(newServicePort));
				//configFileForServicePort(oldServicePort).renameTo(configFileForServicePort(newServicePort));
				writeProperties(newProperties, configFileForServicePort(newServicePort));
				configFileForServicePort(oldServicePort).delete();
				//Thread.sleep(RESTART_DELAY_MS); was this sleep needed for something???
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
			int servicePort = Integer.parseInt(newProperties.getProperty(SERVICE_PORT.toString()));
			doStopCommand(oldProperties);
			waitForProcessToTerminate(oldProperties);
			writeProperties(newProperties, configFileForServicePort(servicePort));
			actuallyLaunchServlet(servicePort);
		}
		else
		{
			log.println("servlet does NOT need to be restarted :-)");
			int servicePort = Integer.parseInt(newProperties.getProperty(SERVICE_PORT.toString()));
			writeProperties(newProperties, configFileForServicePort(servicePort));
		}

	}

	private
	void doStopCommand(Filter filter, PrintStream out, boolean andThenRestart) throws IOException
	{
		if (filter.implicitlyMatchesEverything())
		{
			out.println("stop (or restart) command requires some restrictions or an explicit '--all' flag");
			return;
		}

		final List<Properties> matchingProperties = propertiesFromMatchingConfigFiles(filter);

		try
		{
			doOrderlyDrain(matchingProperties);
		}
		catch (InterruptedException e)
		{
			e.printStackTrace(log);
		}

		int total = 0;
		int success = 0;

		List<String> failures = new ArrayList<String>();

		for (Properties properties : matchingProperties)
		{
			total++;
			String name = humanReadable(properties);

			try
			{
				log.println("stopping: " + name);
				doStopCommand(properties);
				success++;
			}
			catch (Throwable t)
			{
				t.printStackTrace();
				String message = "failed to stop '" + name + "': " + t.toString();
				failures.add(message);
				log.println(message);
			}
		}

		for (Properties properties : matchingProperties)
		{
			try
			{
				waitForProcessToTerminate(properties);
			}
			catch (Throwable t)
			{
				t.printStackTrace();
				String message = humanReadable(properties) + " might not have stopped: " + t.toString();
				failures.add(message);
				total++;
			}
		}

		if (andThenRestart)
		{
			for (Properties properties : matchingProperties)
			{
				try
				{
					final int servicePort = Integer.parseInt(properties.getProperty(SERVICE_PORT.toString()));
					actuallyLaunchServlet(servicePort);
				}
				catch (Throwable t)
				{
					t.printStackTrace();
					String message = humanReadable(properties) + " might not have restarted: " + t.toString();
					failures.add(message);
					total++;
				}
			}

			successOrFailureReport("restarted", total, success, failures, out);
		}
		else
		{
			successOrFailureReport("stopped", total, success, failures, out);
		}
	}

	private
	void doKillCommand(Filter filter, PrintStream out) throws IOException
	{
		if (filter.implicitlyMatchesEverything())
		{
			out.println("kill command requires some restrictions or an explicit '--all' flag");
			return;
		}

		int total = 0;
		int success = 0;

		//List<String> pidsToWaitFor;
		List<String> failures = new ArrayList<String>();

		for (Properties properties : propertiesFromMatchingConfigFiles(filter))
		{
			total++;
			String name = humanReadable(properties);

			try
			{
				log.println("killing: " + name);
				int pid = doKillCommand(properties);
				success++;
			}
			catch (Throwable t)
			{
				t.printStackTrace();
				String message = "failed to kill '" + name + "': " + t.toString();
				failures.add(message);
				log.println(message);
			}
		}

		successOrFailureReport("killed", total, success, failures, out);
	}

	private
	void doRemoveCommand(Filter filter, PrintStream out) throws IOException
	{
		if (filter.implicitlyMatchesEverything())
		{
			out.println("remove command requires some restrictions or an explicit '--all' flag");
			return;
		}

		final
		List<Properties> matchingProperties = propertiesFromMatchingConfigFiles(filter);

		try
		{
			doOrderlyDrain(matchingProperties);
		}
		catch (InterruptedException e)
		{
			e.printStackTrace(log);
		}

		int total = 0;
		int success = 0;

		//List<String> pidsToWaitFor;
		List<String> failures = new ArrayList<String>();

		for (Properties properties : matchingProperties)
		{
			total++;
			String name = humanReadable(properties);

			try
			{
				int servicePort = Integer.parseInt(properties.getProperty(SERVICE_PORT.toString()));

				File configFile = configFileForServicePort(servicePort);

				if (!configFile.exists())
				{
					throw new FileNotFoundException(configFile.toString());
				}

				File warFile = warFileForServicePort(servicePort);

				if (!warFile.exists())
				{
					throw new FileNotFoundException(warFile.toString());
				}

				log.println("stopping: " + name);

				doStopCommand(properties);
				waitForProcessToTerminate(properties);

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
					String absolutePath = siblingDirectory.getAbsolutePath();

					if (absolutePath.indexOf(' ') >= 0 || absolutePath.indexOf("/.") >= 0)
					{
						log.println("cowardly refusing to: rm -rf " + absolutePath);
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
			}
			catch (Throwable t)
			{
				t.printStackTrace();
				String message = "failed to stop & remove '" + name + "': " + t.toString();
				failures.add(message);
				log.println(message);
			}
		}

		successOrFailureReport("removed", total, success, failures, out);
	}

	private
	void doOrderlyDrain(List<Properties> matchingProperties) throws InterruptedException
	{
		//TODO: sort by preferred/specified drain order (which is, service dependency depth)

		Deque<Properties> waiting = new ArrayDeque<Properties>(matchingProperties.size());
		Deque<Properties> failed = new ArrayDeque<Properties>(matchingProperties.size());

		for (Properties properties : matchingProperties)
		{
			switch (sendDrainCommand(properties))
			{
				case PASS:
					break;
				case FAIL:
					failed.add(properties);
					break;
				case DELAY:
					waiting.add(properties);
					break;
			}
		}

		final
		boolean anyProcessingExceptions = !(waiting.isEmpty() && failed.isEmpty());

		int perLoopWaitTime = 1500;
		int waitLimiter = 60000 / perLoopWaitTime;

		while (!waiting.isEmpty() && waitLimiter > 0)
		{
			log.println("waiting for " + waiting.size() + " servlets to drain");

			Thread.sleep(perLoopWaitTime);
			waitLimiter -= perLoopWaitTime;

			//xfer waiting -> reprocess
			Deque<Properties> reprocess = new ArrayDeque<Properties>(waiting);
			waiting.clear();

			for (Properties properties : reprocess)
			{
				switch (sendDrainCommand(properties))
				{
					case PASS:
						break;
					case FAIL:
						failed.add(properties);
						break;
					case DELAY:
						waiting.add(properties);
						break;
				}
			}
		}

		if (anyProcessingExceptions)
		{
			/*
			Just to be doubly extra sure... drain them all again! Otherwise minute problems
			from service interdependencies, delayed work queues, and out-of-order draining
			might cause us to drop something important.
			 */
			log.println("calling drain one more time...");

			for (Properties properties : matchingProperties)
			{
				Thread.sleep(perLoopWaitTime);

				switch (sendDrainCommand(properties))
				{
					case PASS:
						break;
					case FAIL:
						log.println("unable to drain servlet: " + humanReadable(properties));
						//TODO: make this state effect the retval of the remove/shutdown commands.
						//TODO: per-servlet option to allow drain failure to halt remove/shutdown process.
						break;
					case DELAY:
						log.println("ERROR: servlet requests drain-related deferment beyond maximum allowed: " + humanReadable(properties));
						break;
				}
			}
		}
		else
		{
			log.println("perfect drain?");
		}
	}

	private
	DrainResult sendDrainCommand(Properties properties)
	{
		final
		int pid = pid(properties);

		if (pid <= 0)
		{
			return DrainResult.PASS;
		}

		final
		int servicePort = Integer.parseInt(properties.getProperty(SERVICE_PORT.toString()));

		String contextPath = properties.getProperty(CONTEXT_PATH.toString());

		if (contextPath.equals("/"))
		{
			contextPath = "";
		}

		try
		{
			URL url = new URL("http://localhost:" + servicePort + contextPath + "/lifecycle/drain");
			HttpURLConnection connection = (HttpURLConnection) url.openConnection();
			connection.setRequestMethod("POST");
			connection.connect();

			int code = connection.getResponseCode();

			log.println("lifecycle/drain status " + code + " @ " + humanReadable(properties));

			if (code == 408)
			{
				return DrainResult.DELAY;
			}
			else if (code == 202)
			{
				//200 is returned by some frameworks even if the page does not exist, so 202 makes it a bit more intentional.
				return DrainResult.PASS;
			}
		}
		catch (RuntimeException e)
		{
			log.println("lifecycle/drain failure-1 @ " + humanReadable(properties));
			e.printStackTrace(log);
		}
		catch (Exception e)
		{
			log.println("lifecycle/drain failure-2 @ " + humanReadable(properties));
			e.printStackTrace(log);
		}

		return DrainResult.FAIL;
	}

	private
	void doStartCommand(Filter filter, PrintStream out) throws IOException
	{
		if (filter.implicitlyMatchesEverything())
		{
			out.println("start command requires some restrictions or an explicit '--all' flag");
			return;
		}

		int total = 0;
		int success = 0;

		//List<String> pidsToWaitFor;
		List<String> failures = new ArrayList<String>();

		for (Properties properties : propertiesFromMatchingConfigFiles(filter))
		{
			total++;
			String name = humanReadable(properties);

			try
			{
				if (isRunning(properties))
				{
					String message = "already running: " + name;
					log.println(message);
					failures.add(message);
				}
				else
				{
					log.println("starting: " + name);
					actuallyLaunchServlet(Integer.parseInt(properties.getProperty(SERVICE_PORT.toString())));
				}
				success++;
			}
			catch (Throwable t)
			{
				t.printStackTrace();
				String message = "failed to start '" + name + "': " + t.toString();
				failures.add(message);
				log.println(message);
			}
		}

		successOrFailureReport("started", total, success, failures, out);
	}

	public
	void successOrFailureReport(String verbed, int total, int success, List<String> failures, PrintStream out)
	{
		if (success == 0)
		{
			if (total == 0)
			{
				out.println("total failure, filter did not match any servlets");
			}
			else
			{
				out.println("total failure (x" + total + ")");
			}
		}
		else if (success == total)
		{
			out.println("GOOD");
			out.println(verbed + " " + total + " servlet(s)");
		}
		else
		{
			int percent = 100 * success / total;
			out.println(percent + "% compliance, only " + verbed + " " + success + " of " + total + " matching servlet(s)");
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

	public
	String humanReadable(Properties properties)
	{
		String basename = new File(properties.getProperty(ORIGINAL_WAR.toString(), "no-name")).getName();
		String name = properties.getProperty(NAME.toString(), basename);
		String port = properties.getProperty(SERVICE_PORT.toString(), "no-port");
		String path = getContextPath(properties);

		return name + "-" + port + "-" + path;
	}

	private
	int doStopCommand(Properties properties) throws MalformedObjectNameException, ReflectionException,
														IOException, InstanceNotFoundException, AttributeNotFoundException, MBeanException, IntrospectionException
	{
		int pid = pid(properties);
		int servicePort = Integer.parseInt(properties.getProperty(SERVICE_PORT.toString()));
		int jmxPort = Integer.parseInt(properties.getProperty(JMX_PORT.toString()));

		log.println("pid=" + pid + ", jmx=" + jmxPort);

		if (pid <= 1)
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
			try
			{
				JMXUtils.printMemoryUsageGivenJMXPort(jmxPort);
			}
			catch (Exception e)
			{
				log.println("unable to determine final memory usage of process #" + pid);
				e.printStackTrace(log);
			}

			//JMXUtils.tellJettyContainerToStopAtJMXPort(jmxPort); hangs on RMI Reaper
			Runtime.getRuntime().exec("kill " + pid); //still runs the shutdown hooks!!!

			return pid;
		}
	}

	private
	void waitForProcessToTerminate(Properties properties) throws InterruptedException, IOException
	{
		final long startTime = System.currentTimeMillis();
		final long deadline = startTime + 10000;

		while (isRunning(properties))
		{
			if (System.currentTimeMillis() > deadline)
			{
				log.println("process did not terminate: " + humanReadable(properties) + " pid=" + pid(properties));
				return;
			}
			Thread.sleep(200);
		}

		final long duration = System.currentTimeMillis() - startTime;

		String seconds = String.format("%1$.3f", (duration / 1000.0));
		log.println("process has terminated: " + humanReadable(properties) + " (pid=" + pid(properties) + ") in " + seconds + " seconds (wait time)");
		properties.setProperty(PID.toString(), SERVLET_STOPPED_PID);
		writeProperties(properties);
	}

	private
	int doKillCommand(Properties properties) throws IOException
	{
		final int pid = pid(properties);

		if (pid > 0 && isRunning(properties))
		{
			final String command = "kill -9 " + pid; //does not run the shutdown hooks, but sometimes needed if java gets stuck
			log.println(command);
			Runtime.getRuntime().exec(command);

			properties.setProperty(PID.toString(), SERVLET_STOPPED_PID);
			writeProperties(properties);
		}

		return pid;
	}

	//NB: presentAndTrue & presentAndFalse are not complementary (they both return false for NULL or unknown)
	private
	boolean presentAndTrue(Properties p, ServletProp key)
	{
		String s = p.getProperty(key.toString());
		if (s == null || s.length() == 0) return false;
		char c = s.charAt(0);
		return (c == 't' || c == 'T' || c == '1' || c == 'y' || c == 'Y');
	}

	//NB: presentAndTrue & presentAndFalse are not complementary (they both return false for NULL or unknown)
	private
	boolean presentAndFalse(Properties p, ServletProp key)
	{
		String s = p.getProperty(key.toString());
		if (s == null || s.length() == 0) return false;
		char c = s.charAt(0);
		return (c == 'f' || c == 'F' || c == '0' || c == 'n' || c == 'N');
	}

	private static final DateFormat iso_8601_ish                  = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS' UTC'");
	private static final DateFormat iso_8601_compat1              = new SimpleDateFormat("yyyy-MM-dd HH:mm'z'");
	private static final DateFormat compact_iso_8601_ish_filename = new SimpleDateFormat("yyyyMMdd");

	static
	{
		TimeZone timeZone = TimeZone.getTimeZone("UTC");
		Calendar calendar = Calendar.getInstance(timeZone);
		iso_8601_ish.setCalendar(calendar);
		iso_8601_compat1.setCalendar(calendar);
		compact_iso_8601_ish_filename.setCalendar(calendar);
	}

	public
	Date logDate()
	{
		Date now = new Date();
		log.println();
		log.println(iso_8601_ish.format(now));
		return now;
	}

	public
	String stripPathSuffixAndVersionNumber(String name)
	{
		int slash = name.lastIndexOf('/');
		if (slash > 0) name = name.substring(slash + 1);
		int period = name.lastIndexOf('.');
		if (period > 0) name = name.substring(0, period);
		int hypen = name.lastIndexOf('-');
		if (hypen > 0)
		{
			String beforeHypen = name.substring(0, hypen);
			String afterHypen = name.substring(hypen + 1);
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

	public static
	boolean looksLikeVersionNumber(String s)
	{
		for (int i = s.length() - 1; i >= 0; i--)
		{
			char c = s.charAt(i);
			if (c >= '0' && c <= '9') continue;
			if (c == '.') continue;
			return false;
		}
		return s.length() > 0;
	}

	private
	String guessPathFromWar(String warBaseName)
	{
		int hypen = warBaseName.lastIndexOf('-');

		if (hypen > 0)
		{
			System.err.println("guessPathFromWar-1: " + warBaseName);
			return "/" + warBaseName.substring(hypen + 1);
		}
		else
		{
			System.err.println("guessPathFromWar-2: " + warBaseName);
			return "/" + warBaseName;
		}
	}

	private
	Date getDate(Properties p, ServletProp key)
	{
		String value = p.getProperty(key.toString());

		if (value != null)
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

	public
	void tagPresentDate(Properties p, ServletProp key)
	{
		String value = iso_8601_ish.format(new Date());
		p.setProperty(key.toString(), value);
	}

	/**
	 * Writes the properties file in such a way that if it is interfered with (e.g. a full disk), then a
	 * valid copy of the properties will still exist. This is a common unix idiom: write & swap. A side
	 * effect, though, is that we might lose some file attributes (group, mode, xattr).
	 *
	 * @param properties
	 * @throws IOException
	 */
	public
	void writeProperties(Properties properties) throws IOException
	{
		final
		int servicePort = Integer.parseInt(properties.getProperty(SERVICE_PORT.toString()));

		final
		File destination = configFileForServicePort(servicePort);

		writeProperties(properties, destination);
	}

	/**
	 * Writes the properties file in such a way that if it is interfered with (e.g. a full disk), then a
	 * valid copy of the properties will still exist. This is a common unix idiom: write & swap. A side
	 * effect, though, is that we might lose some file attributes (group, mode, xattr).
	 *
	 * @param p
	 * @param destination
	 * @throws IOException
	 */
	public
	void writeProperties(Properties p, File destination) throws IOException
	{
		final
		File swapFile = new File(destination.getParent(), destination.getName() + ".swp");
		{
			final
			FileOutputStream fos = new FileOutputStream(swapFile);

			p.store(fos, "Written by hyperjetty service class");
			fos.close();
		}

		if (swapFile.length() == 0)
		{
			throw new IOException("written config file is empty!");
		}

		if (swapFile.renameTo(destination))
		{
			log.println("wrote: " + destination);
		}
		else
		{
			log.println("overwrite by rename does not work?");

			final
			String date = compact_iso_8601_ish_filename.format(new Date());

			final
			int randomInt = 1000 + new Random(System.nanoTime()).nextInt(9000);

			final
			File step = new File(destination.getParent(), destination.getName() + "." + date + "." + randomInt);

			if (!destination.renameTo(step) || !swapFile.renameTo(destination))
			{
				swapFile.renameTo(step);
				throw new IOException("unable to overwrite config file: " + destination);
			}
			else
			{
				step.delete();
			}
		}
	}

	private
	Filter getFilter(List<String> args) throws IOException
	{
		Filter root = new Filter();
		Filter building = root;

		Iterator<String> i = args.iterator();

		while (i.hasNext())
		{
			String flag = i.next();
			String argument = null;

			{
				int equals = flag.indexOf('=');
				if (equals > 0)
				{
					argument = flag.substring(equals + 1);
					flag = flag.substring(0, equals);
					//log.println("split: flag="+flag+" & arg="+argument);
				}
			}

			//most trailing "s"es can be ignored (except: "unless")
			if (flag.endsWith("s") && !flag.endsWith("unless"))
			{
				flag = flag.substring(0, flag.length() - 1);
				log.println("trimming 's' from flag yields: " + flag);
				if (flag.isEmpty()) throw new IllegalArgumentException("flag reduces to empty string");
			}

			//Trim off leading hypens
			while (flag.charAt(0) == '-')
			{
				flag = flag.substring(1);
				if (flag.isEmpty()) throw new IllegalArgumentException("flag reduces to empty string");
			}

			if (flag.equals("all"))
			{
				building.explicitMatchAll = true;
				continue;
			}

			if (flag.equals("or"))
			{
				building = building.or();
				continue;
			}

			if (flag.equals("except") || flag.equals("and-not") || flag.equals("but-not") || flag.equals("unless"))
			{
				building = building.andNot();
				continue;
			}

			if (flag.endsWith("all-but"))
			{
				building.explicitMatchAll = true;
				building = building.andNot();
				continue;
			}

			if (flag.endsWith("where"))
			{
				building = root.where();
				continue;
			}

			// "filter" may be change
			Filter filter = building;

			//----------- no-argument options

			if (flag.startsWith("without-") && argument == null)
			{
				String optionName = flagToOptionName(flag);
				log.println("* without: " + optionName);
				filter.without(optionName);
				continue;
			}

			//------------
			// A hack to accept entries like --not-port 123 & --except-name bob

			if (flag.startsWith("not-") || flag.startsWith("except-") || flag.startsWith("without-"))
			{
				filter = filter.andNot();
				flag = flag.substring(flag.indexOf('-') + 1);
				log.println("* negated flag: " + flag);
			}

			//------------

			//!!!: this "endsWith" logic has reached the end of it's utility, we should just strip the leading hypens and be done with it.
			if (flag.equals("live"))
			{
				filter.state("alive");
				continue;
			}
			if (flag.equals("alive"))
			{
				filter.state("alive");
				continue;
			}
			if (flag.equals("dead"))
			{
				filter.state("dead");
				continue;
			}
			if (flag.equals("stopped"))
			{
				filter.state("stopped");
				continue;
			}

			if (argument == null)
			{
				try
				{
					argument = i.next();
				}
				catch (NoSuchElementException e)
				{
					throw new IllegalArgumentException(flag + " requires one argument, or is an argument with a missing flag (e.g. maybe 'port' or 'name' should come before it)",
														  e);
				}
				if (argument.length() == 0)
				{
					throw new IllegalArgumentException("argument cannot be the empty string");
				}
				if (argument.charAt(0) == '-')
				{
					throw new IllegalArgumentException("arguments & flags seem to be confused: " + argument);
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
				String optionName = flagToOptionName(flag);
				log.println("* option: " + optionName + " = " + argument);
				filter.option(optionName, argument);
			}
			else if (flag.equals("with"))
			{
				String[] options = argument.split(",");
				for (String optionName : options)
				{
					String value = "TRUE";
					log.println("* option: " + optionName + " = " + value);
					filter.option(optionName, value);
				}
			}
			else if (flag.equals("without"))
			{
				log.println("* without: " + argument);
				filter.without(argument);
			}
			else
			{
				throw new IllegalArgumentException("Unknown command line argument/flag: " + flag);
			}
		}

		//We must straighten out the logical backwards-ness... "where" is the primary filter, if it exists
		if (root.whereFilter != null)
		{
			Filter primary = root.whereFilter;
			Filter setFilter = root;
			root.whereFilter = null;
			primary.kludgeSetFilter = setFilter;
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
		int hypen = flag.indexOf("-", 3);
		if (hypen < 0)
		{
			throw new IllegalArgumentException("does not look like an option flag: " + flag);
		}
		return flag.substring(hypen + 1);
	}

	private
	List<Properties> propertiesFromMatchingConfigFiles(Filter filter) throws IOException
	{
		List<Properties> retval = new ArrayList<Properties>();

		ServletStateChecker servletStateChecker = new PropFileServletChecker();

		File[] files = etcDirectory.listFiles();
		if (files == null) return retval;
		Arrays.sort(files);

		for (File file : files)
		{
			final
			String baseName = file.getName();

			if (!baseName.endsWith(".config"))
			{
				continue;
			}

			Properties p = propertiesFromFile(file);

			if (filter.matches(p, servletStateChecker))
			{
				try
				{
					final
					int servicePort = Integer.parseInt(p.getProperty(SERVICE_PORT.toString()));

					final
					String expectedName = servicePort + ".config";

					if (!baseName.equals(expectedName))
					{
						//TODO: let's extract and use (but still verify) the service port from the filename, therefor supporting a 'rename to move port' or 'forgot to edit file' maintainence workflows.
						log.println("WARNING: " + file + " is for port " + servicePort + ", but is not named: " + expectedName);
					}

					retval.add(p);
				}
				catch (Exception e)
				{
					e.printStackTrace(log);
					//TODO: make this effect the overall command exit value, without inhibiting the work that otherwise could be done.
				}
			}
		}
		return retval;
	}

	private
	List<Properties> propertiesFromAllConfigFiles() throws IOException
	{
		List<Properties> retval = new ArrayList<Properties>();

		File[] files = etcDirectory.listFiles();
		if (files == null) return retval;
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
		public
		ServletState getServletState(Properties p)
		{
			int pid = pid(p);
			if (pid <= 0)
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

}
