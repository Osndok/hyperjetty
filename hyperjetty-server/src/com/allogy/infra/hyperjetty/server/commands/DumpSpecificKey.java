package com.allogy.infra.hyperjetty.server.commands;

import com.allogy.infra.hyperjetty.common.ServletProp;
import com.allogy.infra.hyperjetty.server.CommandUtilities;
import com.allogy.infra.hyperjetty.server.Filter;

import java.io.IOException;
import java.io.PrintStream;
import java.util.List;
import java.util.Properties;

/**
 * Created by robert on 2015-09-24 00:17.
 */
public
class DumpSpecificKey extends AbstractCommand
{
	private final
	ServletProp servletPropKey;

	public
	DumpSpecificKey(ServletProp servletPropKey)
	{
		this.servletPropKey = servletPropKey;
	}

	@Override
	public
	String getDescription()
	{
		return "Lists the "+servletPropKey+" property for matched servlets";
	}

	@Override
	public
	void execute(
					Filter filter,
					List<Properties> matches,
					CommandUtilities commandUtilities,
					PrintStream out
	) throws IOException
	{
		if (matches.isEmpty())
		{
			String message = "no matching servlets";
			out.println(message);
			log.println(message);
			return;
		}

		final
		String keyString = servletPropKey.toString();

		out.println("GOOD");

		for (Properties p : matches)
		{
			String value = p.getProperty(keyString);
			if (value != null)
			{
				out.println(value);
			}
		}
	}
}
