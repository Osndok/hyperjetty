package com.allogy.infra.hyperjetty.server;

import junit.framework.TestCase;
import org.testng.annotations.Test;

/**
 * User: robert
 * Date: 2014/07/01
 * Time: 11:13 AM
 */
@Test
public
class ServiceTest extends TestCase
{
    public
    void testJenkinsSideChannelUrlConverter()
    {
        t("https://jenkins.allogy.com/job/Capillary%20Content%20Editor/207/artifact/target/capillary-wui.war",
          "https://jenkins.allogy.com/artifact-sidechannel/Capillary%20Content%20Editor/builds/207/archive/target/capillary-wui.war");
    }

    private
    void t(String urlInput, String expected)
    {
        String urlOutput=Service.translateJenkinsUrlToSideChannel(urlInput);
        assertEquals(expected, urlOutput);
    }
}
