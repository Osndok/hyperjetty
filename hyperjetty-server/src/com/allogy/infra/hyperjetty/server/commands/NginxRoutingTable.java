package com.allogy.infra.hyperjetty.server.commands;

import com.allogy.infra.hyperjetty.server.CommandUtilities;
import com.allogy.infra.hyperjetty.server.Filter;

import java.io.IOException;
import java.io.PrintStream;
import java.util.List;
import java.util.Properties;

import static com.allogy.infra.hyperjetty.common.ServletProp.SERVICE_PORT;

/**
 * Created by robert on 2015-09-24 00:25.
 */
public
class NginxRoutingTable extends AbstractCommand
{
	@Override
	public
	String getDescription()
	{
		return "Prints an example nginx routing table that could be used to distribute load to the matched servlets";
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
		int tabs = Integer.parseInt(filter.getOption("tabs", "2"));
		String host = filter.getOption("host", "127.0.0.1");

		// http://wiki.nginx.org/HttpUpstreamModule ("server" sub-section)
		String weight = filter.getOption("weight", null);
		String max_fails = filter.getOption("max-fails", null);
		String fail_timeout = filter.getOption("fail-timeout", null);
		String down = filter.getOption("down", null);
		String backup = filter.getOption("backup", null);

		boolean alwaysDown = false;
		boolean downIfNotRunning = false;

		if (down != null)
		{
			if (down.equals("auto"))
			{
				downIfNotRunning = true;
			}
			if (down.equals("true"))
			{
				alwaysDown = true;
			}
		}

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
			String servicePort = properties.getProperty(SERVICE_PORT.toString());
			for (int i = 0; i < tabs; i++)
			{
				out.print("\t");
			}
			out.print(host);
			out.print(':');
			out.print(servicePort);

			if (weight != null)
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

			if (alwaysDown || (downIfNotRunning && !commandUtilities.isRunning(properties)))
			{
				out.print(" down");
			}

			out.println(';');
		}
	}
}
