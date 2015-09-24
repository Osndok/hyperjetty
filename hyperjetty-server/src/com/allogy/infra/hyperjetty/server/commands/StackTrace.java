package com.allogy.infra.hyperjetty.server.commands;

import com.allogy.infra.hyperjetty.server.CommandUtilities;
import com.allogy.infra.hyperjetty.server.Filter;

import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static com.allogy.infra.hyperjetty.common.ServletProp.PID;

/**
 * Created by robert on 2015-09-24 00:10.
 */
public
class StackTrace extends AbstractCommand
{
	@Override
	public
	String getDescription()
	{
		return "Sends a signal to the JVM matching the specified servlets, generating a stack trace in it's log file.";
	}

	@Override
	public
	void execute(
					Filter filter,
					List<Properties> matchedProperties,
					CommandUtilities commandUtilities,
					PrintStream out
	) throws IOException
	{
		int total = 0;
		int success = 0;

		List<String> failures = new ArrayList<String>();

		for (Properties properties : matchedProperties)
		{
			total++;
			String name = commandUtilities.humanReadable(properties);

			try
			{
				int pid = Integer.parseInt(properties.getProperty(PID.toString()));
				log.println("dump-trace: " + name + " (pid=" + pid + ")");

				Process process = Runtime.getRuntime().exec("kill -QUIT " + pid);

				process.waitFor();
				int status = process.exitValue();
				if (status == 0)
				{
					success++;
				}
				else
				{
					String message = "failed to send trace '" + name + "': kill exit status " + status;
					failures.add(message);
					log.println(message);
				}
			}
			catch (Throwable t)
			{
				t.printStackTrace();
				String message = "failed to send trace '" + name + "': " + t.toString();
				failures.add(message);
				log.println(message);
			}
		}

		commandUtilities.successOrFailureReport("traced", total, success, failures, out);
	}
}
