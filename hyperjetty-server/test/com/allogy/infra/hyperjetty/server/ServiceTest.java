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

	public
	void testNameHash()
	{
		int last=-1;

		for (char c='a'; c<='z'; c++)
		{
			int hash=h(c);
			assertTrue(hash > last);
			last=hash;
		}

		assertTrue(last > 980);

		h("alpha");
		h("alpha-bravo");
		h("bravo");
		h("charlie");
		h("zune");
		h("zulu");
		h("zzzzzzzzzzz");
	}

	private
	int h(char c)
	{
		String s=String.valueOf(c);
		int max=1000;
		int hash=PortReservation.nameHash(s, max);
		System.out.println(String.format("%12s / %d -> %d", s, max, hash));

		assertTrue(hash >= 0);
		assertTrue(hash <= max);

		return hash;
	}

	private
	int h(String s)
	{
		int max=1000;
		int hash=PortReservation.nameHash(s, max);
		System.out.println(String.format("%12s / %d -> %d", s, max, hash));

		assertTrue(hash >= 0);
		assertTrue(hash <= max);

		return hash;
	}
}
