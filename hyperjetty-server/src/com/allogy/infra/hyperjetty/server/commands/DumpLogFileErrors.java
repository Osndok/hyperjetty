package com.allogy.infra.hyperjetty.server.commands;

import com.allogy.infra.hyperjetty.server.CommandUtilities;
import com.allogy.infra.hyperjetty.server.Filter;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.util.List;
import java.util.Properties;

/**
 * Created by robert on 2015-09-24 00:31.
 */
public
class DumpLogFileErrors extends AbstractCommand
{
	@Override
	public
	String getDescription()
	{
		return "Prints servlet errors from matching application log files";
	}

	@Override
	public
	void execute(
					Filter filter,
					List<Properties> matchedProperties,
					CommandUtilities util,
					PrintStream out
	) throws IOException
	{
		if (matchedProperties.isEmpty())
		{
			String message = "no matching servlets";
			out.println(message);
			log.println(message);
			return;
		}

		final String logFileSuffix = ".log";

		final boolean multipleMatches = (matchedProperties.size() > 1);

		int numErrors = 0;

		if (!multipleMatches)
		{
			final Properties onlyMatch = matchedProperties.get(0);
			final String display = util.humanReadable(onlyMatch);
			final String filename = util.logFileBaseFromProperties(onlyMatch, false) + logFileSuffix;

			out.println("GOOD");
			out.println("Scanning for servlet errors: " + display + " / " + filename + "\n");
			numErrors = dumpAndCountLogErrors(filename, out, null);
		}
		else
		{
			out.println("GOOD");
			out.println("Scanning for errors in " + matchedProperties.size() + " servlet log files...\n");

			for (Properties properties : matchedProperties)
			{
				final String prefix = util.humanReadable(properties) + ": ";
				final String filename = util.logFileBaseFromProperties(properties, false) + logFileSuffix;
				numErrors += dumpAndCountLogErrors(filename, out, prefix);
			}
		}

		if (numErrors == 0)
		{
			final String message = "No errors found";
			out.println(message);
			log.println(message);
		}
		else
		{
			final String message = "\n" + numErrors + " error(s) found...";
			out.println(message);
			log.println(message);
		}
	}

	private
	int dumpAndCountLogErrors(String filename, PrintStream out, String prefix) throws IOException
	{
		int retval = 0;
		int linesRead = 0;

		final BufferedReader logFile = new BufferedReader(new InputStreamReader(new FileInputStream(filename)));

		String lastLine = logFile.readLine();

		while (lastLine != null)
		{
			linesRead++;

			if (!containsErrorMarker(lastLine))
			{
				lastLine = logFile.readLine();
				continue;
			}

			if (prefix != null) out.print(prefix);
			out.println(lastLine);
			retval++;

			String line = logFile.readLine();
			linesRead++;

			// How many lines to display when we loose track of the log entries. Usually indicates a stack trace, etc.
			int nonLogLinesToDisplay = 3;

			while (line != null && !containsLogLevelMarker(line) && nonLogLinesToDisplay > 0)
			{
				if (prefix != null) out.print(prefix);
				out.println(line);
				line = logFile.readLine();
				linesRead++;
				nonLogLinesToDisplay--;
			}

			lastLine = line;
		}

		log.println(filename + ": read " + linesRead + " lines, found " + retval + " errors");
		return retval;
	}

	private
	boolean containsLogLevelMarker(String line)
	{
        /*
         * "2014-07-03 01:08:04,020 [DEBUG] pages.SelfTest loading page: core/PageCatalog"
         *           1         2         3         4
         * 01234567890123456789012345678901234567890
         */
		final int firstBracket = line.indexOf('[');
		return firstBracket > 23 && firstBracket < 34;
	}

	private
	boolean containsErrorMarker(String line)
	{
		return line.contains("[ERROR");
	}
}
