package com.allogy.infra.hyperjetty.server;

import com.allogy.infra.hyperjetty.common.ServletProp;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Date;
import java.util.List;
import java.util.Properties;

/**
 * Created by robert on 2015-09-23 23:17.
 */
public
interface CommandUtilities
{
	String logFileBaseFromProperties(Properties p, boolean forStartup);
	File configFileForServicePort(int servicePort);
	File warFileForServicePort(int servicePort);
	int pid(Properties properties);
	boolean isRunning(Properties properties);
	String getContextPath(Properties p);
	String humanReadable(Properties properties);
	void successOrFailureReport(String verbed, int total, int success, List<String> failures, PrintStream out);
	Date logDate();
	String stripPathSuffixAndVersionNumber(String name);
	void tagPresentDate(Properties p, ServletProp key);
	void writeProperties(Properties properties) throws IOException;
	void writeProperties(Properties p, File destination) throws IOException;
	int actuallyLaunchServlet(int servicePort) throws IOException;
}
