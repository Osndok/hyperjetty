package com.allogy.infra.hyperjetty.server;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.Properties;

/**
 * Created by robert on 2015-09-23 23:17.
 */
public
interface Command
{
	String getDescription();
	void execute(
					Filter filter,
					List<Properties> matchedProperties,
					CommandUtilities commandUtilities,
					ObjectInputStream in,
					PrintStream out,
					int numFiles
	) throws IOException;
}
