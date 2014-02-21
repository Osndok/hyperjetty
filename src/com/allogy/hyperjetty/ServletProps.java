package com.allogy.hyperjetty;

/**
 * User: robert
 * Date: 2013/05/13
 * Time: 5:09 PM
 */
public enum ServletProps
{
    DATE_CREATED,
    DATE_STARTED,
    DATE_RESPAWNED,
    DEPLOY_DIR,
    HEAP_SIZE,
    JMX_PORT,
    LOG_DATE,            /* A date-like chunk of characters that is included in the log filenames, may include a "-N" suffix */
    NAME,
    OPTIONS,
    ORIGINAL_WAR,
    PATH,
    PID,
    PERM_SIZE,
    @Deprecated PORT_NUMBER_IN_LOG_FILENAME,
    RESPAWN_COUNT,
    SERVICE_PORT,
    STACK_SIZE,
    TAGS,
    VERSION,
    WITHOUT
}
