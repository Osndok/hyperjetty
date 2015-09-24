package com.allogy.infra.hyperjetty.server.commands;

import com.allogy.infra.hyperjetty.server.CommandUtilities;
import com.allogy.infra.hyperjetty.server.Filter;

import java.io.IOException;
import java.io.PrintStream;
import java.util.List;
import java.util.Properties;

import static com.allogy.infra.hyperjetty.common.ServletProp.SERVICE_PORT;

/**
 * Created by robert on 2015-09-23 23:57.
 */
public
class DumpProperties extends AbstractCommand
{
	@Override
	public
	String getDescription()
	{
		return "Lists all the key/value pairs that hj tracks for ONE matching servlet";
	}

	@Override
	public
	void execute(
					Filter filter, List<Properties> matches, CommandUtilities util, PrintStream out
	) throws IOException
	{
		if (matches.size() != 1)
		{
			out.println("expecting precisely one servlet match, found " + matches.size());
			return;
		}

		out.println("GOOD");

		Properties properties = matches.get(0);

		int servicePort = Integer.parseInt(properties.getProperty(SERVICE_PORT.toString()));

		properties.store(out, null);

		String logBase = util.logFileBaseFromProperties(properties, false);
		out.println("LOG=" + logBase + ".log");
		out.println("ACCESS_LOG=" + logBase + ".access");
		out.println("CONFIG_FILE=" + util.configFileForServicePort(servicePort));
		out.println("WAR_FILE=" + util.warFileForServicePort(servicePort));

		int pid = util.pid(properties);
		//NB: PID is already in the printed (as it's in the file)... we only need to output generated or derived data.
		//out.println("PID="+pid);

		if (pid <= 1)
		{
			out.println("STATE=Stopped");
		}
		else if (util.isRunning(properties))
		{
			out.println("STATE=Alive");
		}
		else
		{
			out.println("STATE=Dead");
		}

		String host = filter.getOption("host", "localhost");
		String protocol = filter.getOption("protocol", "http");
		String path = util.getContextPath(properties);

		out.println("URL=" + protocol + "://" + host + ":" + servicePort + path);
	}
}
