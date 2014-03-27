package com.allogy.infra.hyperjetty.common;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.Scanner;

/**
 * User: robert
 * Date: 2013/08/30
 * Time: 3:37 PM
 */
public abstract class FileUtils
{
    static
    String contentsAsString(File file) throws FileNotFoundException
    {
        return new Scanner(file).useDelimiter("\\Z").next();
    }
}
