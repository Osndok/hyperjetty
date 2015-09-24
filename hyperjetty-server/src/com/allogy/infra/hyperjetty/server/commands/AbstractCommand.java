package com.allogy.infra.hyperjetty.server.commands;

import com.allogy.infra.hyperjetty.server.Command;

import java.io.PrintStream;

/**
 * Created by robert on 2015-09-23 23:22.
 */
public abstract
class AbstractCommand implements Command
{
	protected final
	PrintStream log=System.err;
}
