package com.allogy.infra.hyperjetty.server.commands;

import com.allogy.infra.hyperjetty.server.Command;
import com.allogy.infra.hyperjetty.server.CommandUtilities;
import com.allogy.infra.hyperjetty.server.Filter;

import java.io.IOException;
import java.io.PrintStream;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Created by robert on 2015-09-24 00:35.
 */
public
class PrintAvailableCommands extends AbstractCommand
{
	private final
	Map<String, Command> commands;

	public
	PrintAvailableCommands(Map<String, Command> commands)
	{
		this.commands = commands;
	}

	@Override
	public
	String getDescription()
	{
		return "Prints this help message";
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
		for (Map.Entry<String, Command> me : commands.entrySet())
		{
			final
			String name=me.getKey();

			final
			Command command=me.getValue();

			out.print(name);
			out.print('\t');
			out.println(command.getDescription());
		}
	}
}
