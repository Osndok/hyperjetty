package com.allogy.infra.hyperjetty.server.commands;

import com.allogy.infra.hyperjetty.common.ServletName;
import com.allogy.infra.hyperjetty.common.ServletProp;
import com.allogy.infra.hyperjetty.server.CommandUtilities;
import com.allogy.infra.hyperjetty.server.Filter;
import com.allogy.infra.hyperjetty.server.MavenUtils;
import com.allogy.infra.hyperjetty.server.Service;
import com.allogy.infra.hyperjetty.server.internal.PortReservation;

import javax.xml.bind.DatatypeConverter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.PrintStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

import static com.allogy.infra.hyperjetty.common.ServletProp.CONTEXT_PATH;
import static com.allogy.infra.hyperjetty.common.ServletProp.DATE_CREATED;
import static com.allogy.infra.hyperjetty.common.ServletProp.HEAP_SIZE;
import static com.allogy.infra.hyperjetty.common.ServletProp.JMX_PORT;
import static com.allogy.infra.hyperjetty.common.ServletProp.NAME;
import static com.allogy.infra.hyperjetty.common.ServletProp.OPTIONS;
import static com.allogy.infra.hyperjetty.common.ServletProp.ORIGINAL_WAR;
import static com.allogy.infra.hyperjetty.common.ServletProp.PERM_SIZE;
import static com.allogy.infra.hyperjetty.common.ServletProp.PID;
import static com.allogy.infra.hyperjetty.common.ServletProp.SERVICE_PORT;
import static com.allogy.infra.hyperjetty.common.ServletProp.STACK_SIZE;
import static com.allogy.infra.hyperjetty.common.ServletProp.TAGS;
import static com.allogy.infra.hyperjetty.common.ServletProp.VERSION;
import static com.allogy.infra.hyperjetty.common.ServletProp.WITHOUT;

/**
 * Created by robert on 2015-09-24 01:35.
 */
public
class LaunchServlet extends AbstractCommand
{
	private static final int minimumServicePort =10000;
	private static final int minimumJMXPort     =11000;
	private static final boolean USE_BIG_TAPESTRY_DEFAULTS = false;
	private static final int JETTY_VERSION = 8;

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

	private static
	String nodeps_jdk6_base64Encode(String input)
	{
		return DatatypeConverter.printBase64Binary(input.getBytes());
	}

	@Override
	public
	String getDescription()
	{
		return "Given a war file, creates a new servlet instance and returns it's local port number";
	}

	@Override
	public
	void execute(
					Filter filter,
					List<Properties> matchedProperties,
					CommandUtilities util,
					ObjectInputStream in, PrintStream out,
					int numFiles
	) throws IOException
	{

        /* "Mandatory" arguments */
		String war = theOneRequired("war", filter.war);

        /* Optional arguments */
		String name = oneOrNull("name", filter.name);
		String contextPath = oneOrNull("path", filter.path);

		String tag = commaJoinedOrNull("tag", filter.tag);
		String version = commaJoinedOrNull("version", filter.version);

		String heapMemory = oneOrNull("heap", filter.heap);
		String permMemory = oneOrNull("perm", filter.perm);
		String stackMemory = oneOrNull("stack", filter.stack);

		String withoutOptions = commaJoinedOrNull("without", filter.without);

        /* Launch options */
		String dry = filter.getOption("dry", null);
		String returnValue = filter.getOption("return", "port"); //by default, we will print the port number on success

		//String portNumberInLogFilename=filter.getOption("port-based-logs", null);

		String basename = util.stripPathSuffixAndVersionNumber(war);

		log.println("BASE=" + basename);

		boolean nameIsGuessed = false;

		if (name == null)
		{
			name = guessNameFromWar(basename);
			log.println("* guessed application name from war: " + name);
			nameIsGuessed = true;
		}

		log.println("WAR =" + war);
		log.println("NAME=" + name);
		log.println("PATH=" + contextPath);

		PortReservation initialPortReservation;
		{

			if (filter.port != null)
			{
				String servicePortString = oneOrNull("servicePort", filter.port);

				if (filter.jmxPort != null)
				{
					log.println("(!) jmx & service port were both specified");
					String jmxPort = oneOrNull("jmxPort", filter.jmxPort);
					initialPortReservation = PortReservation.exactly(servicePortString, jmxPort);
				}
				else
				{
					log.println("(!) service port was specified");
					initialPortReservation = PortReservation.givenFixedServicePort(servicePortString,
																					  minimumServicePort,
																					  minimumJMXPort);
				}
			}
			else if (filter.jmxPort != null)
			{
				log.println("(!) jmx port was specified");
				String jmxPort = oneOrNull("jmxPort", filter.jmxPort);
				initialPortReservation = PortReservation.givenFixedJMXPort(jmxPort, minimumServicePort, minimumJMXPort);
			}
			else
			{
				try
				{
					initialPortReservation = PortReservation.nameCentric(name, minimumServicePort, minimumJMXPort);
				}
				catch (Exception e)
				{
					e.printStackTrace(log);
					initialPortReservation = PortReservation.startingAt(minimumServicePort, minimumJMXPort);
				}
			}
		}

		final PortReservation portReservation;
		final int servicePort;
		final int jmxPort;
		{
			final int initialServicePort = initialPortReservation.getServicePort();
			int tempServicePort = initialServicePort;

			if (util.configFileForServicePort(initialServicePort).exists())
			{
				log.println("port " + initialServicePort + " is already allocated (config file exists)");
				if (filter.port != null)
				{
					readAll(in);
					out.println("ERROR: the specified port is already allocated: " + initialServicePort);
					return;
				}
				if (filter.jmxPort != null)
				{
					readAll(in);
					out.println("ERROR: the equivalent service port (for specified JMX port) is already allocated: " + initialPortReservation.getJmxPort() + " (jmx) ---> " + initialServicePort);
					return;
				}
			}

			PortReservation previousReservation = initialPortReservation;
			PortReservation newReservation = null;

			while (util.configFileForServicePort(tempServicePort).exists())
			{
				log.println("WARN: port already configured: " + tempServicePort);

				try
				{
					//This is expected to "fill in" gaps left by the name-centric initial allocation...
					newReservation = PortReservation.startingAt(previousReservation.getServicePort(),
																   previousReservation.getJmxPort());
				}
				catch (Exception e)
				{
					e.printStackTrace(log);
					//...but if it fails, then we will fall back to the original round-robin allocating logic.
					newReservation = PortReservation.startingAt(minimumServicePort, minimumJMXPort);
				}

				previousReservation.release();

				tempServicePort = newReservation.getServicePort();

				if (tempServicePort == initialServicePort)
				{
					throw new IllegalStateException("attempting to acquire a port reservation wrapped back around to " + tempServicePort + " this instance is probably 'full'...");
				}

				previousReservation = newReservation;
			}

			if (newReservation == null)
			{
				portReservation = initialPortReservation;
			}
			else
			{
				portReservation = newReservation;
			}

			servicePort = portReservation.getServicePort();
			jmxPort = portReservation.getJmxPort();
		}

		log.println("PORT=" + servicePort);
		log.println("JMX =" + jmxPort);

		final
		File warFile = util.warFileForServicePort(servicePort);

		final
		File configFile = util.configFileForServicePort(servicePort);

		final InputStream warSource;
		final Long bytes;
		{
			if (numFiles == 0 && looksLikeURL(war))
			{
				log.println("reading war file from url (3rd-party to client/server): " + war);
				warSource = inputStreamFromPossiblyKnownSourceLikeJenkins(war);
				bytes = null;
			}
			else if (numFiles != 1)
			{
				if (numFiles == 0)
				{
					out.println("service side did not receive the war file, make sure it is reachable client side and that you have specified");
				}
				else
				{
					out.println("expecting precisely one file... the war-file; make sure no other command-line args are file names");
				}

				log.println("client supplied " + numFiles + " files (expecting 1 [or a url] for launch command)");
				return;
			}
			else
			{
				String originalFilename = in.readUTF();

				if (!originalFilename.equals(war))
				{
					log.println("WARN: war != filename: " + originalFilename);
				}

				if (!originalFilename.endsWith(".war"))
				{
					String message = "cowardly refusing to process war file that does not end in '.war', if you don't know what you are doing please ask for help!";
					log.println(message);
					out.println(message);
					return;
				}

				warSource = in;
				bytes = in.readLong();

				log.println("receiving via hj-control connection: " + originalFilename + " " + bytes + " bytes => " + warFile);
			}
		}

		//Stream at most 'bytes' (could be null, meaning 'all of them') from warSource to warFile
		{
			FileOutputStream fos = new FileOutputStream(warFile);
			try
			{
				int max = 4096;
				byte[] buffer = new byte[max];

				Long bytesToGo = bytes;
				int read = max;

				while ((read = warSource.read(buffer, 0, read)) > 0)
				{
					fos.write(buffer, 0, read);

					if (bytesToGo != null)
					{
						bytesToGo -= read;
						read = (int) Math.min(bytesToGo, max);
					}
				}

				fos.flush();

				util.logDate();
				log.println("finished writing: " + warFile);
			}
			finally
			{
				fos.close();
			}
		}

		Properties p = null;

		try
		{
			p = getEmbeddedAppProperties(warFile);
		}
		catch (Exception e)
		{
			log.println("Unable to read properties from war file");
			e.printStackTrace();
		}

		if (p == null)
		{
			//No 'app.properties'... that's okay.
			p = new Properties();
		}

		if (nameIsGuessed)
		{
			if (p.containsKey(NAME.toString()))
			{
				//the name provided in the app properties overrides any guessed name
				name = p.getProperty(NAME.toString());
				log.println("NAME=" + name + " (updated from app.properties)");
			}

			p.setProperty(NAME.toString(), name);
		}
		else
		{
			//the name provided on the command line overrides any in the app properties.
			p.setProperty(NAME.toString(), name);
		}

		//At this point, we at least know our application name! This will let us derive our log base, etc.

		if (version == null)
		{
			try
			{
				version = MavenUtils.readVersionNumberFromWarFile(warFile);
				log.println("? version from maven: " + version);
			}
			catch (Exception e)
			{
				e.printStackTrace();
			}
		}

		if (version == null)
		{
			version = guessVersionNumberFromWarName(war);
			log.println("? version from war name: " + version);
		}

		if (p == null)
		{
			log.println("INFO: the war file has no embedded servlet properties");
			p = new Properties();
		}

		//----- from this point, command line options (at the top of the function) override in-built options

		if (version != null)
		{
			maybeSet(p, VERSION, version);
		}

		if (contextPath != null)
		{
			p.setProperty(CONTEXT_PATH.toString(), contextPath);
		}

		if (tag != null)
		{
			p.setProperty(TAGS.toString(), tag);
		}

		maybeSet(p, SERVICE_PORT, Integer.toString(servicePort));
		maybeSet(p, JMX_PORT, Integer.toString(jmxPort));
		maybeSet(p, ORIGINAL_WAR, war);

		if (name != null && !nameIsGuessed)
		{
			p.setProperty(NAME.toString(), name);
		}

		if (withoutOptions != null)
		{
			p.setProperty(WITHOUT.toString(), withoutOptions);
		}

		if (USE_BIG_TAPESTRY_DEFAULTS)
		{
			//FROM: http://tapestry.apache.org/specific-errors-faq.html
			maybeSet(p, HEAP_SIZE, "600m"); // -Xmx
			maybeSet(p, PERM_SIZE, "512m"); // -XX:MaxPermSize=
			maybeSet(p, STACK_SIZE, "1m"); // -Xss
		}
		else
		{
			maybeSet(p, HEAP_SIZE, "300m"); // -Xmx
			maybeSet(p, PERM_SIZE, "200m"); // -XX:MaxPermSize=
			maybeSet(p, STACK_SIZE, "1m"); // -Xss
		}

		if (heapMemory != null)
		{
			p.setProperty(HEAP_SIZE.toString(), heapMemory);
		}

		if (permMemory != null)
		{
			p.setProperty(PERM_SIZE.toString(), permMemory);
		}

		if (stackMemory != null)
		{
			p.setProperty(STACK_SIZE.toString(), stackMemory);
		}

		maybeSet(p, PID, "-1");

		if (filter.options != null)
		{
			String options = commaJoinedOrNull("options", filter.options.keySet());
			log.println("launch options: " + options);
			p.setProperty(OPTIONS.toString(), options);
		}

		util.tagPresentDate(p, DATE_CREATED);
		util.writeProperties(p, configFile);

		portReservation.release();

		String retval = Integer.toString(servicePort);

		if (dry != null && dry.toLowerCase().equals("true"))
		{
			log.println("not launching servlet, as dry option is true");
			if (returnValue.equals("pid"))
			{
				retval = "dry";
			}
		}
		else
		{
			final
			int pid = util.actuallyLaunchServlet(servicePort);

			if (returnValue.equals("pid"))
			{
				retval = Integer.toString(pid);
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
				log.println("recognizing jenkins url: " + url);
				url = translateJenkinsUrlToSideChannel(url);
				//log.println("Authorization: "+JENKINS_AUTHORIZATION_HEADER);
				urlConnection = new URL(url).openConnection();
				urlConnection.setRequestProperty("Authorization", JENKINS_AUTHORIZATION_HEADER);
			}
			else
			{
				urlConnection = new URL(url).openConnection();
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
		final String[] bits = url.split("/");
		final StringBuilder sb = new StringBuilder();

		int i = 0;
		for (String bit : bits)
		{
			switch (i)
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
		String retval = sb.toString();

		System.err.println("Translated Jenkins URL to artifact side channel: " + retval);
		return retval;
	}


	private
	boolean looksLikeURL(String fileOrUrl)
	{
		final int i = fileOrUrl.indexOf("://");
		return (i > 0) && (i < 10);
	}

	private
	void readAll(InputStream in) throws IOException
	{
		while (in.read() >= 0) ;
	}

	private
	String guessVersionNumberFromWarName(String name)
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
			if (Service.looksLikeVersionNumber(afterHypen))
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
	String guessNameFromWar(final String warBaseName)
	{
		String retval;
		{
			int period = warBaseName.lastIndexOf('.');
			if (period > 0)
			{
				retval = warBaseName.substring(0, period);
			}
			else
			{
				retval = warBaseName;
			}
		}

		return ServletName.filter(retval);
	}

	private
	String commaJoinedOrNull(String fieldName, Set<String> set)
	{
		if (set == null)
		{
			return null;
		}

		Iterator<String> i = set.iterator();
		StringBuilder sb = new StringBuilder();
		sb.append(i.next());

		while (i.hasNext())
		{
			sb.append(',');
			sb.append(i.next());
		}

		return sb.toString();
	}

	private
	String theOneRequired(String fieldName, Set<String> set)
	{
		if (set == null)
		{
			throw new IllegalArgumentException(fieldName + " required, but not provided");
		}
		Iterator<String> i = set.iterator();
		String value = i.next();
		if (i.hasNext())
		{
			throw new IllegalArgumentException("precisely one " + fieldName + " required, but " + set.size() + " provided");
		}
		return value;
	}

	private
	String oneOrNull(String fieldName, Set<String> set)
	{
		if (set == null)
		{
			return null;
		}
		Iterator<String> i = set.iterator();
		String value = i.next();
		if (i.hasNext())
		{
			throw new IllegalArgumentException("precisely one " + fieldName + " required, but " + set.size() + " provided");
		}
		return value;
	}

	private
	Properties getEmbeddedAppProperties(File warFile) throws IOException
	{
		JarFile jarFile = new JarFile(warFile);
		try
		{
			ZipEntry zipEntry = jarFile.getEntry("WEB-INF/app.properties");
			if (zipEntry == null)
			{
				log.println("No app.properties");
				return null;
			}
			InputStream inputStream = jarFile.getInputStream(zipEntry);
			if (inputStream == null)
			{
				log.println("cannot get inputstream for app.properties");
				return null;
			}
			else
			{
				try
				{
					Properties retval = new Properties();
					retval.load(inputStream);
					log.println("read " + retval.size() + " properties from embedded app.properties file");
					return retval;
				}
				finally
				{
					inputStream.close();
				}
			}
		}
		finally
		{
			jarFile.close();
		}
	}

	private static
	void maybeSet(Properties p, ServletProp keyCode, String ifNotPresent)
	{
		String key = keyCode.toString();
		if (!p.containsKey(key))
		{
			p.setProperty(key, ifNotPresent);
		}
	}
}
