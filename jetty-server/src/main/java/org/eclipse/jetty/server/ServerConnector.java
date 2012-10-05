//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.channels.Channel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.SelectChannelEndPoint;
import org.eclipse.jetty.io.SelectorManager;
import org.eclipse.jetty.io.SelectorManager.ManagedSelector;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.Name;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.Scheduler;

/**
 * This {@link Connector} implementation is the primary connector for the
 * Jetty server over TCP/IP.  By the use of various {@link ConnectionFactory} instances it is able
 * to accept connections for HTTP, SPDY and WebSocket, either directly or over SSL.
 * <p>
 * The connector is a fully asynchronous NIO based implementation that by default will
 * use all the commons services (eg {@link Executor}, {@link Scheduler})  of the
 * passed {@link Server} instance, but all services may also be constructor injected
 * into the connector so that it may operate with dedicated or otherwise shared services.
 * <p>
 * <h2>Connection Factories</h2>
 * Various convenience constructors are provided to assist with common configurations of
 * ConnectionFactories, whose generic use is described in {@link AbstractConnector}.
 * If no connection factories are passed, then the connector will
 * default to use a {@link HttpConnectionFactory}.  If an non null {@link SslContextFactory}
 * instance is passed, then this used to instantiate a {@link SslConnectionFactory} which is
 * prepended to the other passed or default factories.
 * <p>
 * <h2>Selectors</h2>
 * The connector will use the {@link Executor} service to execute a number of Selector Tasks,
 * which are implemented to each use a NIO {@link Selector} instance to asynchronously
 * schedule a set of accepted connections.  It is the selector thread that will call the
 * {@link Callback} instances passed in the {@link EndPoint#fillInterested(Object, Callback)} or
 * {@link EndPoint#write(Object, Callback, java.nio.ByteBuffer...)} methods.  It is expected
 * that these callbacks may do some non-blocking IO work, but will always dispatch to the
 * {@link Executor} service any blocking, long running or application tasks.
 * <p>
 * The default number of selectors is equal to the number of processors available to the JVM,
 * which should allow optimal performance even if all the connections used are performing
 * significant non-blocking work in the callback tasks.
 *
 */
@ManagedObject("HTTP connector using NIO ByteChannels and Selectors")
public class ServerConnector extends AbstractNetworkConnector
{
    private final SelectorManager _manager;
    private volatile ServerSocketChannel _acceptChannel;
    private volatile boolean _inheritChannel = false;
    private volatile int _localPort = -1;
    private volatile int _acceptQueueSize = 128;
    private volatile boolean _reuseAddress = true;
    private volatile int _lingerTime = -1;


    public ServerConnector(
        @Name("server") Server server)
    {
        this(server,null,null,null,0,0,new HttpConnectionFactory());
    }

    public ServerConnector(
        @Name("server") Server server,
        @Name("factories") ConnectionFactory... factories)
    {
        this(server,null,null,null,0,0,factories);
    }

    public ServerConnector(
        @Name("server") Server server,
        @Name("sslContextFactory") SslContextFactory sslContextFactory)
    {
        this(server,null,null,null,0,0,AbstractConnectionFactory.getFactories(sslContextFactory,new HttpConnectionFactory()));
    }

    public ServerConnector(
        @Name("server") Server server,
        @Name("sslContextFactory") SslContextFactory sslContextFactory,
        @Name("factories") ConnectionFactory... factories)
    {
        this(server,null,null,null,0,0,AbstractConnectionFactory.getFactories(sslContextFactory,factories));
    }

    /**
     * @param server    The server this connector will be added to. Must not be null.
     * @param executor  An executor for this connector or null to use the servers executor
     * @param scheduler A scheduler for this connector or null to use the servers scheduler
     * @param pool      A buffer pool for this connector or null to use a default {@link ByteBufferPool}
     * @param acceptors the number of acceptor threads to use, or 0 for a default value.
     * @param factories Zero or more {@link ConnectionFactory} instances.
     */
    public ServerConnector(
        @Name("server") Server server,
        @Name("executor") Executor executor,
        @Name("scheduler") Scheduler scheduler,
        @Name("bufferPool") ByteBufferPool pool,
        @Name("acceptors") int acceptors,
        @Name("selectors") int selectors,
        @Name("factories") ConnectionFactory... factories)
    {
        super(server,executor,scheduler,pool,acceptors,factories);
        _manager = new ServerConnectorManager(getExecutor(), getScheduler(), selectors > 0 ? selectors : Runtime.getRuntime().availableProcessors());
        addBean(_manager, true);
    }

    @Override
    public boolean isOpen()
    {
        ServerSocketChannel channel = _acceptChannel;
        return channel!=null && channel.isOpen();
    }

    /**
     * @return whether this connector uses a channel inherited from the JVM.
     * @see System#inheritedChannel()
     */
    public boolean isInheritChannel()
    {
        return _inheritChannel;
    }

    /**
     * <p>Sets whether this connector uses a channel inherited from the JVM.</p>
     * <p>If true, the connector first tries to inherit from a channel provided by the system.
     * If there is no inherited channel available, or if the inherited channel is not usable,
     * then it will fall back using {@link ServerSocketChannel}.</p>
     * <p>Use it with xinetd/inetd, to launch an instance of Jetty on demand. The port
     * used to access pages on the Jetty instance is the same as the port used to
     * launch Jetty.</p>
     *
     * @param inheritChannel whether this connector uses a channel inherited from the JVM.
     */
    public void setInheritChannel(boolean inheritChannel)
    {
        _inheritChannel = inheritChannel;
    }

    @Override
    public void open() throws IOException
    {
        if (_acceptChannel == null)
        {
            ServerSocketChannel serverChannel = null;
            if (isInheritChannel())
            {
                Channel channel = System.inheritedChannel();
                if (channel instanceof ServerSocketChannel)
                    serverChannel = (ServerSocketChannel)channel;
                else
                    LOG.warn("Unable to use System.inheritedChannel() [{}]. Trying a new ServerSocketChannel at {}:{}", channel, getHost(), getPort());
            }

            if (serverChannel == null)
            {
                serverChannel = ServerSocketChannel.open();

                InetSocketAddress bindAddress = getHost() == null ? new InetSocketAddress(getPort()) : new InetSocketAddress(getHost(), getPort());
                serverChannel.socket().bind(bindAddress, getAcceptQueueSize());
                serverChannel.socket().setReuseAddress(getReuseAddress());

                _localPort = serverChannel.socket().getLocalPort();
                if (_localPort <= 0)
                    throw new IOException("Server channel not bound");

                addBean(serverChannel);
            }

            serverChannel.configureBlocking(true);
            addBean(serverChannel);

            _acceptChannel = serverChannel;
        }
    }

    @Override
    public <C> Future<C> shutdown(C c)
    {
        // TODO shutdown all the connections
        return super.shutdown(c);
    }

    @Override
    public void close()
    {
        ServerSocketChannel serverChannel = _acceptChannel;
        _acceptChannel = null;

        if (serverChannel != null)
        {
            removeBean(serverChannel);

            // If the interrupt did not close it, we should close it
            if (serverChannel.isOpen())
            {
                try
                {
                    serverChannel.close();
                }
                catch (IOException e)
                {
                    LOG.warn(e);
                }
            }
        }
        // super.close();
        _localPort = -2;
    }

    @Override
    public void accept(int acceptorID) throws IOException
    {
        ServerSocketChannel serverChannel = _acceptChannel;
        if (serverChannel != null && serverChannel.isOpen())
        {
            SocketChannel channel = serverChannel.accept();
            channel.configureBlocking(false);
            Socket socket = channel.socket();
            configure(socket);
            _manager.accept(channel);
        }
    }

    protected void configure(Socket socket)
    {
        try
        {
            socket.setTcpNoDelay(true);
            if (_lingerTime >= 0)
                socket.setSoLinger(true, _lingerTime / 1000);
            else
                socket.setSoLinger(false, 0);
        }
        catch (SocketException e)
        {
            LOG.ignore(e);
        }
    }

    public SelectorManager getSelectorManager()
    {
        return _manager;
    }

    @Override
    public Object getTransport()
    {
        return _acceptChannel;
    }

    @Override
    public int getLocalPort()
    {
        return _localPort;
    }

    protected SelectChannelEndPoint newEndPoint(SocketChannel channel, ManagedSelector selectSet, SelectionKey key) throws IOException
    {
        return new SelectChannelEndPoint(channel, selectSet, key, getScheduler(), getIdleTimeout());
    }

    /**
     * @return the linger time
     * @see Socket#getSoLinger()
     */
    public int getLingerTime()
    {
        return _lingerTime;
    }

    /**
     * @param lingerTime the linger time. Use -1 to disable.
     * @see Socket#setSoLinger(boolean, int)
     */
    public void setSoLingerTime(int lingerTime)
    {
        _lingerTime = lingerTime;
    }

    /**
     * @return the accept queue size
     */
    public int getAcceptQueueSize()
    {
        return _acceptQueueSize;
    }

    /**
     * @param acceptQueueSize the accept queue size (also known as accept backlog)
     */
    public void setAcceptQueueSize(int acceptQueueSize)
    {
        _acceptQueueSize = acceptQueueSize;
    }

    /**
     * @return whether the server socket reuses addresses
     * @see ServerSocket#getReuseAddress()
     */
    public boolean getReuseAddress()
    {
        return _reuseAddress;
    }

    /**
     * @param reuseAddress whether the server socket reuses addresses
     * @see ServerSocket#setReuseAddress(boolean)
     */
    public void setReuseAddress(boolean reuseAddress)
    {
        _reuseAddress = reuseAddress;
    }

    private final class ServerConnectorManager extends SelectorManager
    {
        private ServerConnectorManager(Executor executor, Scheduler scheduler, int selectors)
        {
            super(executor, scheduler, selectors);
        }

        @Override
        protected SelectChannelEndPoint newEndPoint(SocketChannel channel, ManagedSelector selectSet, SelectionKey selectionKey) throws IOException
        {
            return ServerConnector.this.newEndPoint(channel, selectSet, selectionKey);
        }

        @Override
        public Connection newConnection(SocketChannel channel, EndPoint endpoint, Object attachment) throws IOException
        {
            return getDefaultConnectionFactory().newConnection(ServerConnector.this, endpoint);
        }
    }
}
