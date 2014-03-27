package com.allogy.infra.hyperjetty.server;

import java.util.Properties;

/**
 * User: robert
 * Date: 2013/09/24
 * Time: 12:50 PM
 */
public interface ServletStateChecker
{
    ServletState getServletState(Properties p);
}
