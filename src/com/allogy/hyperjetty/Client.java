package com.allogy.hyperjetty;

import java.io.*;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * User: robert
 * Date: 2013/05/13
 * Time: 2:10 PM
 */
public class Client
{
    private final Socket socket;
    private final InputStream inputStream;
    private final OutputStream outputStream;
    private boolean extraOutput=false;

    public
    Client(String host, int port) throws IOException
    {
        this.socket=new Socket(host, port);
        this.inputStream=socket.getInputStream();
        this.outputStream=socket.getOutputStream();
    }

    public static
    void main(String[] args)
    {
        String host=Service.systemPropertyOrEnvironment("HOST", "localhost");
        String port=Service.systemPropertyOrEnvironment("CONTROL_PORT", "5000");

        List<File> files=new ArrayList();

        boolean thisArgIsHost=false;
        boolean thisArgIsPort=false;

        for (String arg : args) {
            if (isReadableFile(arg))
            {
                files.add(new File(arg));
            }
            if (thisArgIsHost)
            {
                host=arg;
                thisArgIsHost=false;
            }
            else if (thisArgIsPort)
            {
                port=arg;
                thisArgIsPort=false;
            }
            else if (isOptionFlag(arg, "-host") || arg.equals("-H"))
            {
                thisArgIsHost=true;
            }
            else if (isOptionFlag(arg, "-hj-port") || arg.equals("-P"))
            {
                thisArgIsPort=true;
            }
        }

        /*
        System.err.println("HJ_HOST="+host);
        System.err.println("HJ_PORT="+port);
        */

        try {
            Client client=new Client(host, Integer.parseInt(port));
            client.sendCommandAndFiles(args, files);

            if (client.goodServerResponse())
            {
                if (!client.extraOutput) {
                    System.err.println("success");
                }
                System.exit(0);
            }
            else
            {
                System.err.println("failure");
                System.exit(2);
            }
        } catch (Throwable t) {
            t.printStackTrace();
            System.out.println("failure");
            System.exit(1);
        }
    }

    private static final char EOL='\n';
    private static final String ENCODING="UTF-8";

    public
    void sendCommandAndFiles(String[] args, List<File> files) throws IOException
    {
        ObjectOutputStream out = new ObjectOutputStream(outputStream);

        out.writeInt(args.length);

        for (String s : args) {
            out.writeUTF(s);
        }

        out.writeInt(files.size());

        for (File file : files)
        {
            long filesize=file.length();
            out.writeUTF(file.toString());
            out.writeLong(filesize);

            byte[] buffer=new byte[4096];
            int bytes;

            FileInputStream fis=new FileInputStream(file);
            while ((bytes=fis.read(buffer))>0)
            {
                out.write(buffer, 0, bytes);
            }
            fis.close();

            if (filesize != file.length())
            {
                throw new IOException("file changed/grew as we were reading it: "+file);
            }
        }

        out.writeUTF("EOT");
        out.flush();

        //out.close(); ... closes the socket too... :(
        socket.shutdownOutput();
    }

    public
    boolean goodServerResponse() throws IOException
    {
        BufferedReader br=new BufferedReader(new InputStreamReader(inputStream));

        String line=br.readLine();

        if (line==null)
        {
            System.err.println("Empty server response");
            return false;
        }

        boolean retval;

        if (line.equals("GOOD"))
        {
            retval=true;
        }
        else
        {
            retval=false;
            System.err.println(line);
        }

        while ((line=br.readLine())!=null)
        {
            extraOutput=true;
            if (retval) {
                System.out.println(line);
            } else {
                System.err.println(line);
            }
        }

        br.close();
        socket.close();

        return retval;
    }

    private static
    boolean isOptionFlag(String arg, String flag)
    {
        int i=arg.indexOf(flag);
        //Matches "-flag" or "--flag"
        return (i>=0 && i<=1);
    }

    private static
    boolean isReadableFile(String s)
    {
        File file=new File(s);
        return file.isFile() && file.canRead();
    }
}
