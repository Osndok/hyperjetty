package com.allogy.infra.hyperjetty.server.commands;

import com.allogy.infra.hyperjetty.common.ProcessUtils;
import com.allogy.infra.hyperjetty.common.ServletProp;
import com.allogy.infra.hyperjetty.server.CommandUtilities;
import com.allogy.infra.hyperjetty.server.Filter;
import com.allogy.infra.hyperjetty.server.internal.JMXUtils;
import com.allogy.infra.hyperjetty.server.internal.ServletMemoryUsage;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.PrintStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static com.allogy.infra.hyperjetty.common.ServletProp.HEAP_SIZE;
import static com.allogy.infra.hyperjetty.common.ServletProp.JMX_PORT;
import static com.allogy.infra.hyperjetty.common.ServletProp.NAME;
import static com.allogy.infra.hyperjetty.common.ServletProp.PERM_SIZE;
import static com.allogy.infra.hyperjetty.common.ServletProp.RESPAWN_COUNT;
import static com.allogy.infra.hyperjetty.common.ServletProp.SERVICE_PORT;
import static com.allogy.infra.hyperjetty.common.ServletProp.TAGS;
import static com.allogy.infra.hyperjetty.common.ServletProp.VERSION;

/**
 * Created by robert on 2015-09-24 00:38.
 */
public
class DumpServletStatus extends AbstractCommand
{
	@Override
	public
	String getDescription()
	{
		return "Prints a table of useful information for running/matched servlets";
	}

	private
	CommandUtilities util;

	@Override
	public
	void execute(
					Filter filter,
					List<Properties> matchingProperties,
					CommandUtilities util,
					ObjectInputStream in, PrintStream out,
					int numFiles
	) throws IOException
	{
		//Kludgy...
		this.util=util;

		final
		Map<Properties, String> rpsPerServlet = getRequestsPerSecondForEachServlet(matchingProperties);

		out.println("GOOD");

		boolean anyVersions = anyPropertyHas(matchingProperties, VERSION);
		boolean anyTags = anyPropertyHas(matchingProperties, TAGS);
		boolean anyRespawns = anyPropertyHas(matchingProperties, RESPAWN_COUNT);

		if (true)
		{ //scope for A & B

			StringBuilder a = new StringBuilder();
			StringBuilder b = new StringBuilder();

			//Basically... don't print those columns that are already specified by filter...
			// and print the predictably-sized fields first, then likely-to-be-small, then rest
			// PORT | LIFE | HEAP | PERM | VERSION | PATH | App-name

			if (filter.port == null)
			{
				a.append(" Port  |");
				b.append("-------+");
				//append(" 10000 |");
			}

			a.append("  PID  | Life | RPS  |  Heap Usage   | PermGen Usage ");
			b.append("-------+------+------+---------------+---------------");
			//append(" 12345 | LIVE | 100m |  100% of 999m |  100% of 999m ");
			//append(" 12333 | DEAD |  10k |   10% of   3g |   10% of   9m ");
			//append("    -1 | STOP | 999  |    - N/A -    |    - N/A -    ");
			//append(" 12333 | DEAD | 0.03 |   10% of   3g |   10% of   9m ");

			if (anyRespawns)
			{
				a.append("| Death ");
				b.append("+-------");
				//append("|  9999 ");
				//append("|       ");
				//append("|   10k ");
			}

			if (filter.version == null && anyVersions)
			{
				a.append("| Version  ");
				b.append("+----------");
				//append("| v0.3.31  ");
				//append("|   N/A    ");
				//append("| snapshot ");
			}

			if (filter.path == null)
			{
				a.append("| Request Path ");
				b.append("+--------------");
				//append("| /latest      ");
				//append("| /wui         ");
				//append("| /statements  ");
			}

			if (filter.tag == null && anyTags)
			{
				a.append("|     Tags    ");
				b.append("+-------------");
				//append("| production  ");
				//append("| testing     ");
				//append("| development ");
				//append("| integration ");
			}

			if (filter.name == null)
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

		int count = 0;

		StringBuilder line = new StringBuilder(200);

		for (Properties p : matchingProperties)
		{
			count++;

			if (filter.port == null)
			{
				//append(" Port  |");
				//append("-------+");
				//append(" 10000 |");
				line.append(' ');
				line.append(String.format("%5s", p.getProperty(SERVICE_PORT.toString(), "Err")));
				line.append(" |");
			}

			//append("  PID  | Life | RPS  |  Heap Usage   | PermGen Usage ");
			//append("-------+------+------+---------------+---------------");
			//append(" 12345 | LIVE | 121m |  100% of 999m |  100% of 999m ");
			//append(" 12333 | DEAD |  32k |   10% of   3g |   10% of   9m ");
			//append("    -1 | STOP |      |    - N/A -    |    - N/A -    ");
			//append(" 12222 | LIVE | 10.8 |    No JMX     |    No JMX     ");
			//append("    -1 | STOP | 0.01 |    - N/A -    |    - N/A -    ");
			int pid = util.pid(p);

			line.append(' ');

			if (pid == -1)
			{
				//-blank- is more meaningful than "-1"...
				line.append("     ");
			}
			else
			{
				line.append(String.format("%5d", pid));
			}

			ServletMemoryUsage smu = null;

			if (pid <= 1)
			{
				String heap = p.getProperty(HEAP_SIZE.toString(), "n/a").toLowerCase();
				String perm = p.getProperty(PERM_SIZE.toString(), "n/a").toLowerCase();

				line.append(" | STOP |      |  ");
				line.append(String.format("%12s", heap));
				line.append(" |  ");
				line.append(String.format("%12s", perm));
				line.append(" ");
			}
			else if (ProcessUtils.isRunning(pid))
			{
				String requestsPerSecond = rpsPerServlet.get(p);
				String jmxString = p.getProperty(JMX_PORT.toString());

				if (jmxString != null)
				{
					try
					{
						int jmxPort = Integer.parseInt(jmxString);
						smu = JMXUtils.getMemoryUsageGivenJMXPort(jmxPort);
					}
					catch (Throwable t)
					{
						t.printStackTrace();
					}
				}

				if (smu == null)
				{
					//line.append(" | LIVE | 123m |    No JMX     |    No JMX     ");
					String heap = p.getProperty(HEAP_SIZE.toString(), "n/a").toLowerCase();
					String perm = p.getProperty(PERM_SIZE.toString(), "n/a").toLowerCase();

					line.append(String.format(" | LIVE | %4s |  ", requestsPerSecond));
					line.append(String.format("???? of %4s", heap));
					line.append(" |  ");
					line.append(String.format("???? of %4s", perm));
					line.append(" ");
				}
				else
				{
					line.append(String.format(" | LIVE | %4s |  ", requestsPerSecond));
					line.append(smu.getHeapSummary());
					line.append(" |  ");
					line.append(smu.getPermGenSummary());
					line.append(' ');
				}
			}
			else
			{
				String heap = p.getProperty(HEAP_SIZE.toString(), "n/a").toLowerCase();
				String perm = p.getProperty(PERM_SIZE.toString(), "n/a").toLowerCase();

				line.append(" | DEAD |      |  ");
				line.append(String.format("%12s", heap));
				line.append(" |  ");
				line.append(String.format("%12s", perm));
				line.append(" ");
			}

			if (anyRespawns)
			{
				//append("| Death ");
				//append("+-------");
				//append("|  9999 ");
				//append("| 1,234 ");
				//append("| 99999 ");
				//append("|       ");
				//append("|   10k ");
				final
				String integerString = p.getProperty(RESPAWN_COUNT.toString());

				if (integerString == null)
				{
					line.append("|       ");
				}
				else
				{
					final
					int i = Integer.parseInt(integerString);

					if (i > 9999)
					{
						line.append(String.format("| %4sk ", i / 1000));
					}
					else
					{
						line.append(String.format("| %5s ", integerString));
					}

				}
			}

			if (filter.version == null && anyVersions)
			{
				final
				String versionString = p.getProperty(VERSION.toString(), "");

				//append("| Version  ");
				//append("+----------");
				//append("| v0.3.31  ");
				//append("|   N/A    ");
				//append("| snapshot ");
				line.append("| ");

				if (versionString.length() <= 8)
				{
					line.append(String.format("%-8s", versionString));
				}
				else
				{
					line.append(versionString.substring(0, 8));
				}

				line.append(' ');
			}

			if (filter.path == null)
			{
				//append("| Request Path ");
				//append("+--------------");
				//append("| /latest      ");
				//append("| /wui         ");
				//append("| /statements  ");
				line.append("| ");
				line.append(String.format("%-12s", util.getContextPath(p)));
				line.append(" ");
			}

			if (filter.tag == null && anyTags)
			{
				final
				String tagString = reorderTagString(p.getProperty(TAGS.toString(), ""));

				//append("|     Tag     ");
				//append("+-------------");
				//append("| production  ");
				//append("| testing     ");
				//append("| development ");
				//append("| integration ");
				line.append("| ");

				if (tagString.length() <= 11)
				{
					line.append(String.format("%-11s", tagString));
				}
				else
				{
					line.append(tagString.substring(0, 11));
				}

				line.append(' ');
			}

			if (filter.name == null)
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

		String message = "stats matched " + count + " servlets";
		out.println(message);
		log.println(message);
	}


	private
	Map<Properties, String> getRequestsPerSecondForEachServlet(List<Properties> propertiesList)
	{
		final
		Map<Properties, String> retval = new HashMap<Properties, String>(propertiesList.size());

		for (Properties properties : propertiesList)
		{
			String requestsPerSecondString;

			try
			{
				requestsPerSecondString = getRequestsPerSecond(properties);
			}
			catch (Exception e)
			{
				e.printStackTrace(log);
				requestsPerSecondString = "ERR";
			}

			retval.put(properties, requestsPerSecondString);
		}

		return retval;
	}

	private
	String getRequestsPerSecond(Properties properties) throws IOException
	{
		final
		int pid = util.pid(properties);

		if (pid <= 0)
		{
			return "";
		}

		final
		String servlet = util.humanReadable(properties);

		final
		int port = Integer.parseInt(properties.getProperty(SERVICE_PORT.toString()));

		final
		URLConnection connection = new URL("http://127.0.0.1:" + port + "/stats/").openConnection();

		final
		BufferedReader br = new BufferedReader(new InputStreamReader(connection.getInputStream()));

		try
		{
			final
			String prefix = "Per second: ";

			String line;

			while ((line = br.readLine()) != null)
			{
				if (line.startsWith(prefix))
				{
					final
					String retval;
					{
						final
						int lessThan = line.indexOf('<');

						if (lessThan > 0)
						{
							retval = line.substring(prefix.length(), lessThan);
						}
						else
						{
							retval = line.substring(prefix.length());
						}
					}

					log.println(servlet + " is at " + retval + " requests-per-second");
					return retval;
				}
			}

			return "n/a";
		}
		finally
		{
			br.close();
		}
	}

	private
	String reorderTagString(String in)
	{
		String[] bits = in.split(",");

		if (bits.length == 1) return in;

		List<String> sortable = new ArrayList<String>(bits.length);

		Collections.addAll(sortable, bits);
		Collections.sort(sortable, tagComparator);

		final
		StringBuilder sb = new StringBuilder();

		for (String s : sortable)
		{
			sb.append(s);
			sb.append(',');
		}

		sb.deleteCharAt(sb.length() - 1);

		return sb.toString();
	}

	private final
	Comparator<String> tagComparator = new Comparator<String>()
	{
		@Override
		public
		int compare(String a, String b)
		{
			int aL = a.length();
			int bL = b.length();

			if (aL == bL)
			{
				//Tags of the same size are alphabetical.
				return a.compareTo(b);
			}
			else
			{
				//Short tags before long tags...
				return (aL - bL);
			}
		}
	};

	private
	boolean anyPropertyHas(List<Properties> matchingProperties, ServletProp key)
	{
		String keyString = key.toString();

		for (Properties property : matchingProperties)
		{
			if (property.containsKey(keyString))
			{
				return true;
			}
		}

		return false;
	}

}
