package com.allogy.infra.hyperjetty.server.commands;

import com.allogy.infra.hyperjetty.server.CommandUtilities;
import com.allogy.infra.hyperjetty.server.Filter;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.Properties;

/**
 * Created by robert on 2015-09-23 23:17.
 */
public
class DumpLogFileNames extends AbstractCommand
{
	private final
	String suffix;

	private final
	String description;

	public
	DumpLogFileNames(boolean accessLogs)
	{
		if (accessLogs)
		{
			this.suffix = ".access";
			this.description = "Prints the path to the servlet's access logs (one line per request)";
		}
		else
		{
			this.suffix = ".log";
			this.description = "Prints the path to the servlet's application logs (debugging information)";
		}
	}

	@Override
	public
	String getDescription()
	{
		return description;
	}

	public
	void execute(
					Filter filter,
					List<Properties> matchedProperties,
					CommandUtilities commandUtilities,
					ObjectInputStream in, PrintStream out,
					int numFiles
	) throws IOException
	{
		out.println("GOOD");

		if (matchedProperties.isEmpty())
		{
			String message = "no matching servlets";
			out.println(message);
			log.println(message);
			return;
		}

		for (Properties properties : matchedProperties)
		{
			String filename = commandUtilities.logFileBaseFromProperties(properties, false) + suffix;
			out.println(filename);
			log.println(filename);
		}
	}
}
