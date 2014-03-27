package com.allogy.infra.hyperjetty.runtime;

import org.eclipse.jetty.server.bio.SocketConnector;
import org.newsclub.net.unix.AFUNIXServerSocket;
import org.newsclub.net.unix.AFUNIXSocketAddress;

import java.io.IOException;
import java.net.ServerSocket;

/**
 * User: robert
 * Date: 2014/03/27
 * Time: 12:55 AM
 */
public
class UNIXSocketConnector extends SocketConnector
{
    private final AFUNIXSocketAddress socketAddress;

    UNIXSocketConnector(AFUNIXServerSocket _serverSocket, AFUNIXSocketAddress socketAddress)
    {
        this._serverSocket=_serverSocket;
        this.socketAddress=socketAddress;
    }

    @Override
    protected ServerSocket newServerSocket(String host, int port,int backlog) throws IOException
    {
        AFUNIXServerSocket serverSocket=AFUNIXServerSocket.newInstance();
        serverSocket.bind(socketAddress, backlog);
        return serverSocket;
    }

    /* mostly copied from SocketConnector.java */
    @Override
    public void open() throws IOException
    {
        // Create a new server socket and set to non blocking mode
        if (_serverSocket==null || _serverSocket.isClosed())
            _serverSocket= newServerSocket(getHost(),getPort(),getAcceptQueueSize());
        //throws: _serverSocket.setReuseAddress(getReuseAddress());
        _localPort=_serverSocket.getLocalPort();
        /*also throws...
        if (_localPort<=0)
            throw new IllegalStateException("port not allocated for "+this);
        */
    }
}

