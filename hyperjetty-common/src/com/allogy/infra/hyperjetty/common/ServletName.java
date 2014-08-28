package com.allogy.infra.hyperjetty.common;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Created by robert on 8/28/14.
 */
public
class ServletName
{
	public static
	String filter(String retval)
	{
		retval=maybeTrimSuffix(retval, "-server");
		retval=maybeTrimSuffix(retval, "-service");
		retval=maybeTrimSuffix(retval, "-gateway");
		retval=maybeTrimSuffix(retval, "-backend");

		return retval;
	}

	public static
	String maybeTrimSuffix(String tractor, String trailer)
	{
		if (tractor.endsWith(trailer))
		{
			return tractor.substring(0, tractor.length()-trailer.length());
		}
		else
		{
			return tractor;
		}
	}

	public static
	void main(String[] argArray)
	{
		List<String> args = new ArrayList<String>(Arrays.asList(argArray));
		final String function=args.remove(0);

		if (function.equals("filter"))
		{
			for (String arg : args)
			{
				System.out.println(filter(arg));
			}
		}
		else
		{
			throw new UnsupportedOperationException("unknown function: "+function);
		}
	}
}
