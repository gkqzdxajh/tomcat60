/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.apache.tomcat.util.net;

import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.Executor;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.jni.Address;
import org.apache.tomcat.jni.Error;
import org.apache.tomcat.jni.File;
import org.apache.tomcat.jni.Library;
import org.apache.tomcat.jni.OS;
import org.apache.tomcat.jni.Poll;
import org.apache.tomcat.jni.Pool;
import org.apache.tomcat.jni.SSL;
import org.apache.tomcat.jni.SSLContext;
import org.apache.tomcat.jni.SSLSocket;
import org.apache.tomcat.jni.Socket;
import org.apache.tomcat.jni.Status;
import org.apache.tomcat.util.res.StringManager;

/**
 * APR tailored thread pool, providing the following services:
 * <ul>
 * <li>Socket acceptor thread</li>
 * <li>Socket poller thread</li>
 * <li>Sendfile thread</li>
 * <li>Worker threads pool</li>
 * </ul>
 *
 * When switching to Java 5, there's an opportunity to use the virtual
 * machine's thread pool.
 *
 * @author Mladen Turk
 * @author Remy Maucherat
 */
public class AprEndpoint extends AbstractEndpoint {


    // -------------------------------------------------------------- Constants


    protected static Log log = LogFactory.getLog(AprEndpoint.class);

    protected static StringManager sm =
        StringManager.getManager("org.apache.tomcat.util.net.res");


    /**
     * The Request attribute key for the cipher suite.
     */
    public static final String CIPHER_SUITE_KEY = "javax.servlet.request.cipher_suite";

    /**
     * The Request attribute key for the key size.
     */
    public static final String KEY_SIZE_KEY = "javax.servlet.request.key_size";

    /**
     * The Request attribute key for the client certificate chain.
     */
    public static final String CERTIFICATE_KEY = "javax.servlet.request.X509Certificate";

    /**
     * The Request attribute key for the session id.
     * This one is a Tomcat extension to the Servlet spec.
     */
    public static final String SESSION_ID_KEY = "javax.servlet.request.ssl_session";


    // ----------------------------------------------------------------- Fields


    /**
     * Available workers.
     */
    protected WorkerStack workers = null;


    /**
     * Running state of the endpoint.
     */
    protected volatile boolean running = false;


    /**
     * Will be set to true whenever the endpoint is paused.
     */
    protected volatile boolean paused = false;


    /**
     * Track the initialization state of the endpoint.
     */
    protected boolean initialized = false;


    /**
     * Current worker threads busy count.
     */
    protected int curThreadsBusy = 0;


    /**
     * Current worker threads count.
     */
    protected int curThreads = 0;


    /**
     * Sequence number used to generate thread names.
     */
    protected int sequence = 0;


    /**
     * Root APR memory pool.
     */
    protected long rootPool = 0;


    /**
     * Server socket "pointer".
     */
    protected long serverSock = 0;


    /**
     * APR memory pool for the server socket.
     */
    protected long serverSockPool = 0;


    /**
     * SSL context.
     */
    protected long sslContext = 0;

    /* Acceptor thread array */
    private Acceptor acceptors[] = null;

    // ------------------------------------------------------------- Properties


    /**
     * Defer accept.
     */
    protected boolean deferAccept = true;
    public void setDeferAccept(boolean deferAccept) { this.deferAccept = deferAccept; }
    public boolean getDeferAccept() { return deferAccept; }


    /**
     * External Executor based thread pool.
     */
    protected Executor executor = null;
    public void setExecutor(Executor executor) { this.executor = executor; }
    public Executor getExecutor() { return executor; }


    /**
     * Maximum amount of worker threads.
     */
    protected int maxThreads = 200;
    public void setMaxThreads(int maxThreads) {
        this.maxThreads = maxThreads;
        if (running) {
            synchronized(workers) {
                workers.resize(maxThreads);
            }
        }
    }
    public int getMaxThreads() {
        if (executor != null) {
            return -1;
        } else {
            return maxThreads;
        }
    }


    /**
     * Priority of the acceptor and poller threads.
     */
    protected int threadPriority = Thread.NORM_PRIORITY;
    public void setThreadPriority(int threadPriority) { this.threadPriority = threadPriority; }
    public int getThreadPriority() { return threadPriority; }


    /**
     * Size of the socket poller.
     */
    protected int pollerSize = 8 * 1024;
    public void setPollerSize(int pollerSize) { this.pollerSize = pollerSize; }
    public int getPollerSize() { return pollerSize; }


    /**
     * Size of the sendfile (= concurrent files which can be served).
     */
    protected int sendfileSize = 1 * 1024;
    public void setSendfileSize(int sendfileSize) { this.sendfileSize = sendfileSize; }
    public int getSendfileSize() { return sendfileSize; }


    /**
     * Server socket port.
     */
    protected int port;
    public int getPort() { return port; }
    public void setPort(int port ) { this.port=port; }


    /**
     * Address for the server socket.
     */
    protected InetAddress address;
    public InetAddress getAddress() { return address; }
    public void setAddress(InetAddress address) { this.address = address; }


    /**
     * Handling of accepted sockets.
     */
    protected Handler handler = null;
    public void setHandler(Handler handler ) { this.handler = handler; }
    public Handler getHandler() { return handler; }


    /**
     * Allows the server developer to specify the backlog that
     * should be used for server sockets. By default, this value
     * is 100.
     */
    protected int backlog = 100;
    public void setBacklog(int backlog) { if (backlog > 0) this.backlog = backlog; }
    public int getBacklog() { return backlog; }


    /**
     * Socket TCP no delay.
     */
    protected boolean tcpNoDelay = false;
    public boolean getTcpNoDelay() { return tcpNoDelay; }
    public void setTcpNoDelay(boolean tcpNoDelay) { this.tcpNoDelay = tcpNoDelay; }


    /**
     * Socket linger.
     */
    protected int soLinger = 100;
    public int getSoLinger() { return soLinger; }
    public void setSoLinger(int soLinger) { this.soLinger = soLinger; }


    /**
     * Socket timeout.
     */
    protected int soTimeout = -1;
    public int getSoTimeout() { return soTimeout; }
    public void setSoTimeout(int soTimeout) { this.soTimeout = soTimeout; }


    /**
     * Keep-Alive timeout.
     */
    protected int keepAliveTimeout = -1;
    public int getKeepAliveTimeout() { return keepAliveTimeout; }
    public void setKeepAliveTimeout(int keepAliveTimeout) { this.keepAliveTimeout = keepAliveTimeout; }


    /**
     * Poll interval, in microseconds. The smaller the value, the more CPU the poller
     * will use, but the more responsive to activity it will be.
     */
    protected int pollTime = 2000;
    public int getPollTime() { return pollTime; }
    public void setPollTime(int pollTime) { if (pollTime > 0) { this.pollTime = pollTime; } }


    /**
     * The default is true - the created threads will be
     *  in daemon mode. If set to false, the control thread
     *  will not be daemon - and will keep the process alive.
     */
    protected boolean daemon = true;
    public void setDaemon(boolean b) { daemon = b; }
    public boolean getDaemon() { return daemon; }


    /**
     * Name of the thread pool, which will be used for naming child threads.
     */
    protected String name = "TP";
    public void setName(String name) { this.name = name; }
    public String getName() { return name; }


    /**
     * Use endfile for sending static files.
     */
    protected boolean useSendfile = Library.APR_HAS_SENDFILE;
    public void setUseSendfile(boolean useSendfile) { this.useSendfile = useSendfile; }
    public boolean getUseSendfile() { return useSendfile; }


    /**
     * Allow comet request handling.
     */
    protected boolean useComet = true;
    public void setUseComet(boolean useComet) { this.useComet = useComet; }
    public boolean getUseComet() { return useComet; }


    /**
     * Acceptor thread count.
     */
    protected int acceptorThreadCount = 0;
    public void setAcceptorThreadCount(int acceptorThreadCount) { this.acceptorThreadCount = acceptorThreadCount; }
    public int getAcceptorThreadCount() { return acceptorThreadCount; }


    /**
     * Sendfile thread count.
     */
    protected int sendfileThreadCount = 0;
    public void setSendfileThreadCount(int sendfileThreadCount) { this.sendfileThreadCount = sendfileThreadCount; }
    public int getSendfileThreadCount() { return sendfileThreadCount; }


    /**
     * Poller thread count.
     */
    protected int pollerThreadCount = 0;
    public void setPollerThreadCount(int pollerThreadCount) { this.pollerThreadCount = pollerThreadCount; }
    public int getPollerThreadCount() { return pollerThreadCount; }


    /**
     * The socket poller.
     */
    protected Poller[] pollers = null;
    protected int pollerRoundRobin = 0;
    public Poller getPoller() {
        pollerRoundRobin = (pollerRoundRobin + 1) % pollers.length;
        return pollers[pollerRoundRobin];
    }


    /**
     * The socket poller used for Comet support.
     */
    protected Poller[] cometPollers = null;
    protected int cometPollerRoundRobin = 0;
    public Poller getCometPoller() {
        cometPollerRoundRobin = (cometPollerRoundRobin + 1) % cometPollers.length;
        return cometPollers[cometPollerRoundRobin];
    }


    /**
     * The static file sender.
     */
    protected Sendfile[] sendfiles = null;
    protected int sendfileRoundRobin = 0;
    public Sendfile getSendfile() {
        sendfileRoundRobin = (sendfileRoundRobin + 1) % sendfiles.length;
        return sendfiles[sendfileRoundRobin];
    }


    /**
     * Dummy maxSpareThreads property.
     */
    public int getMaxSpareThreads() { return 0; }


    /**
     * Dummy minSpareThreads property.
     */
    public int getMinSpareThreads() { return 0; }


    /**
     * Unlock timeout.
     */
    protected int unlockTimeout = 250;
    public int getUnlockTimeout() { return unlockTimeout; }
    public void setUnlockTimeout(int unlockTimeout) {
        this.unlockTimeout = unlockTimeout;
    }


    /**
     * SSL engine.
     */
    protected boolean SSLEnabled = false;
    public boolean isSSLEnabled() { return SSLEnabled; }
    public void setSSLEnabled(boolean SSLEnabled) { this.SSLEnabled = SSLEnabled; }


    /**
     * SSL protocols.
     */
    protected String SSLProtocol = "all";
    public String getSSLProtocol() { return SSLProtocol; }
    public void setSSLProtocol(String SSLProtocol) { this.SSLProtocol = SSLProtocol; }


    /**
     * SSL password (if a cert is encrypted, and no password has been provided, a callback
     * will ask for a password).
     */
    protected String SSLPassword = null;
    public String getSSLPassword() { return SSLPassword; }
    public void setSSLPassword(String SSLPassword) { this.SSLPassword = SSLPassword; }


    /**
     * SSL cipher suite.
     */
    protected String SSLCipherSuite = "ALL";
    public String getSSLCipherSuite() { return SSLCipherSuite; }
    public void setSSLCipherSuite(String SSLCipherSuite) { this.SSLCipherSuite = SSLCipherSuite; }


    /**
     * SSL certificate file.
     */
    protected String SSLCertificateFile = null;
    public String getSSLCertificateFile() { return SSLCertificateFile; }
    public void setSSLCertificateFile(String SSLCertificateFile) { this.SSLCertificateFile = SSLCertificateFile; }


    /**
     * SSL certificate key file.
     */
    protected String SSLCertificateKeyFile = null;
    public String getSSLCertificateKeyFile() { return SSLCertificateKeyFile; }
    public void setSSLCertificateKeyFile(String SSLCertificateKeyFile) { this.SSLCertificateKeyFile = SSLCertificateKeyFile; }


    /**
     * SSL certificate chain file.
     */
    protected String SSLCertificateChainFile = null;
    public String getSSLCertificateChainFile() { return SSLCertificateChainFile; }
    public void setSSLCertificateChainFile(String SSLCertificateChainFile) { this.SSLCertificateChainFile = SSLCertificateChainFile; }


    /**
     * SSL CA certificate path.
     */
    protected String SSLCACertificatePath = null;
    public String getSSLCACertificatePath() { return SSLCACertificatePath; }
    public void setSSLCACertificatePath(String SSLCACertificatePath) { this.SSLCACertificatePath = SSLCACertificatePath; }


    /**
     * SSL CA certificate file.
     */
    protected String SSLCACertificateFile = null;
    public String getSSLCACertificateFile() { return SSLCACertificateFile; }
    public void setSSLCACertificateFile(String SSLCACertificateFile) { this.SSLCACertificateFile = SSLCACertificateFile; }


    /**
     * SSL CA revocation path.
     */
    protected String SSLCARevocationPath = null;
    public String getSSLCARevocationPath() { return SSLCARevocationPath; }
    public void setSSLCARevocationPath(String SSLCARevocationPath) { this.SSLCARevocationPath = SSLCARevocationPath; }


    /**
     * SSL CA revocation file.
     */
    protected String SSLCARevocationFile = null;
    public String getSSLCARevocationFile() { return SSLCARevocationFile; }
    public void setSSLCARevocationFile(String SSLCARevocationFile) { this.SSLCARevocationFile = SSLCARevocationFile; }


    /**
     * SSL verify client.
     */
    protected String SSLVerifyClient = "none";
    public String getSSLVerifyClient() { return SSLVerifyClient; }
    public void setSSLVerifyClient(String SSLVerifyClient) { this.SSLVerifyClient = SSLVerifyClient; }


    /**
     * SSL verify depth.
     */
    protected int SSLVerifyDepth = 10;
    public int getSSLVerifyDepth() { return SSLVerifyDepth; }
    public void setSSLVerifyDepth(int SSLVerifyDepth) { this.SSLVerifyDepth = SSLVerifyDepth; }


    // --------------------------------------------------------- Public Methods

    protected boolean SSLHonorCipherOrder = false;
    /**
     * Set to <code>true</code> to enforce the <i>server's</i> cipher order
     * instead of the default which is to allow the client to choose a
     * preferred cipher.
     */
    public void setSSLHonorCipherOrder(boolean SSLHonorCipherOrder) { this.SSLHonorCipherOrder = SSLHonorCipherOrder; }
    public boolean getSSLHonorCipherOrder() { return SSLHonorCipherOrder; }

    /**
     * Disables compression of the SSL stream. This thwarts CRIME attack
     * and possibly improves performance by not compressing uncompressible
     * content such as JPEG, etc.
     */
    protected boolean SSLDisableCompression = false;

    /**
     * Set to <code>true</code> to disable SSL compression. This thwarts CRIME
     * attack.
     */
    public void setSSLDisableCompression(boolean SSLDisableCompression) { this.SSLDisableCompression = SSLDisableCompression; }
    public boolean getSSLDisableCompression() { return SSLDisableCompression; }

    /**
     * Number of keepalive sockets.
     */
    public int getKeepAliveCount() {
        if (pollers == null) {
            return 0;
        } else {
            int keepAliveCount = 0;
            for (int i = 0; i < pollers.length; i++) {
                keepAliveCount += pollers[i].getKeepAliveCount();
            }
            return keepAliveCount;
        }
    }


    /**
     * Number of sendfile sockets.
     */
    public int getSendfileCount() {
        if (sendfiles == null) {
            return 0;
        } else {
            int sendfileCount = 0;
            for (int i = 0; i < sendfiles.length; i++) {
                sendfileCount += sendfiles[i].getSendfileCount();
            }
            return sendfileCount;
        }
    }


    /**
     * Return the amount of threads that are managed by the pool.
     *
     * @return the amount of threads that are managed by the pool
     */
    public int getCurrentThreadCount() {
        if (executor != null) {
            return -1;
        } else {
            return curThreads;
        }
    }

    /**
     * Return the amount of threads that are in use
     *
     * @return the amount of threads that are in use
     */
    public int getCurrentThreadsBusy() {
        if (executor!=null) {
            return -1;
        } else {
            return workers!=null?curThreads - workers.size():0;
        }
    }


    /**
     * Return the state of the endpoint.
     *
     * @return true if the endpoint is running, false otherwise
     */
    public boolean isRunning() {
        return running;
    }


    /**
     * Return the state of the endpoint.
     *
     * @return true if the endpoint is paused, false otherwise
     */
    public boolean isPaused() {
        return paused;
    }


    // ----------------------------------------------- Public Lifecycle Methods


    /**
     * Initialize the endpoint.
     */
    public void init()
        throws Exception {

        if (initialized)
            return;

        if (rootPool != 0) {
            // A previous init() call failed
            throw new IllegalStateException(
                    sm.getString("endpoint.apr.previousInitFailed"));
        }
        
        // Create the root APR memory pool
        rootPool = Pool.create(0);
        // Create the pool for the server socket
        serverSockPool = Pool.create(rootPool);
        // Create the APR address that will be bound
        String addressStr = null;
        if (address == null) {
            addressStr = null;
        } else {
            addressStr = address.getHostAddress();
        }
        int family = Socket.APR_INET;
        if (Library.APR_HAVE_IPV6) {
            if (addressStr == null) {
                if (!OS.IS_BSD && !OS.IS_WIN32 && !OS.IS_WIN64)
                    family = Socket.APR_UNSPEC;
            } else if (addressStr.indexOf(':') >= 0) {
                family = Socket.APR_UNSPEC;
            }
         }

        long inetAddress = Address.info(addressStr, family,
                port, 0, rootPool);
        // Create the APR server socket
        serverSock = Socket.create(Address.getInfo(inetAddress).family,
                Socket.SOCK_STREAM,
                Socket.APR_PROTO_TCP, rootPool);
        if (OS.IS_UNIX) {
            Socket.optSet(serverSock, Socket.APR_SO_REUSEADDR, 1);
        }
        // Deal with the firewalls that tend to drop the inactive sockets
        Socket.optSet(serverSock, Socket.APR_SO_KEEPALIVE, 1);
        // Bind the server socket
        int ret = Socket.bind(serverSock, inetAddress);
        if (ret != 0) {
            throw new Exception(sm.getString("endpoint.init.bind", "" + ret, Error.strerror(ret)));
        }
        // Start listening on the server socket
        ret = Socket.listen(serverSock, backlog);
        if (ret != 0) {
            throw new Exception(sm.getString("endpoint.init.listen", "" + ret, Error.strerror(ret)));
        }
        if (OS.IS_WIN32 || OS.IS_WIN64) {
            // On Windows set the reuseaddr flag after the bind/listen
            Socket.optSet(serverSock, Socket.APR_SO_REUSEADDR, 1);
        }

        // Sendfile usage on systems which don't support it cause major problems
        if (useSendfile && !Library.APR_HAS_SENDFILE) {
            useSendfile = false;
        }

        // Initialize thread count defaults for acceptor, poller and sendfile
        if (acceptorThreadCount == 0) {
            // FIXME: Doesn't seem to work that well with multiple accept threads
            acceptorThreadCount = 1;
        }
        if (pollerThreadCount == 0) {
            if ((OS.IS_WIN32 || OS.IS_WIN64) && (pollerSize > 1024)) {
                // The maximum per poller to get reasonable performance is 1024
                pollerThreadCount = pollerSize / 1024;
                // Adjust poller size so that it won't reach the limit
                pollerSize = pollerSize - (pollerSize % 1024);
            } else {
                // No explicit poller size limitation
                pollerThreadCount = 1;
            }
        }
        if (sendfileThreadCount == 0) {
            if ((OS.IS_WIN32 || OS.IS_WIN64) && (sendfileSize > 1024)) {
                // The maximum per poller to get reasonable performance is 1024
                sendfileThreadCount = sendfileSize / 1024;
                // Adjust poller size so that it won't reach the limit
                sendfileSize = sendfileSize - (sendfileSize % 1024);
            } else {
                // No explicit poller size limitation
                // FIXME: Default to one per CPU ?
                sendfileThreadCount = 1;
            }
        }

        // Delay accepting of new connections until data is available
        // Only Linux kernels 2.4 + have that implemented
        // on other platforms this call is noop and will return APR_ENOTIMPL.
        if (deferAccept) {
            if (Socket.optSet(serverSock, Socket.APR_TCP_DEFER_ACCEPT, 1) == Status.APR_ENOTIMPL) {
                deferAccept = false;
            }
        }

        // Initialize SSL if needed
        if (SSLEnabled) {

            // SSL protocol
            int value = SSL.SSL_PROTOCOL_NONE;
            if (SSLProtocol == null || SSLProtocol.length() == 0) {
                value = SSL.SSL_PROTOCOL_ALL;
            } else {
                for (String protocol : SSLProtocol.split("\\+")) {
                    protocol = protocol.trim();
                    if ("SSLv2".equalsIgnoreCase(protocol)) {
                        value |= SSL.SSL_PROTOCOL_SSLV2;
                    } else if ("SSLv3".equalsIgnoreCase(protocol)) {
                        value |= SSL.SSL_PROTOCOL_SSLV3;
                    } else if ("TLSv1".equalsIgnoreCase(protocol)) {
                        value |= SSL.SSL_PROTOCOL_TLSV1;
                    } else if ("TLSv1.1".equalsIgnoreCase(protocol)) {
                        value |= SSL.SSL_PROTOCOL_TLSV1_1;
                    } else if ("TLSv1.2".equalsIgnoreCase(protocol)) {
                        value |= SSL.SSL_PROTOCOL_TLSV1_2;
                    } else if ("all".equalsIgnoreCase(protocol)) {
                        value |= SSL.SSL_PROTOCOL_ALL;
                    } else {
                        // Protocol not recognized, fail to start as it is safer than
                        // continuing with the default which might enable more than the
                        // is required
                        throw new Exception(sm.getString(
                                "endpoint.apr.invalidSslProtocol", SSLProtocol));
                    }
                }
            }

            // Create SSL Context
            try {
                sslContext = SSLContext.make(rootPool, value, SSL.SSL_MODE_SERVER);
            } catch (Exception e) {
                // If the sslEngine is disabled on the AprLifecycleListener
                // there will be an Exception here but there is no way to check
                // the AprLifecycleListener settings from here
                throw new Exception(
                        sm.getString("endpoint.apr.failSslContextMake"), e);
            }

            // Set cipher order: client (default) or server
            if (SSLHonorCipherOrder) {
                boolean orderCiphersSupported = false;
                try {
                    orderCiphersSupported = SSL.hasOp(SSL.SSL_OP_CIPHER_SERVER_PREFERENCE);
                    if (orderCiphersSupported)
                        SSLContext.setOptions(sslContext, SSL.SSL_OP_CIPHER_SERVER_PREFERENCE);
                } catch (UnsatisfiedLinkError e) {
                    // Ignore
                }
                if (!orderCiphersSupported) {
                    // OpenSSL does not support ciphers ordering.
                    log.warn(sm.getString("endpoint.warn.noHonorCipherOrder",
                                          SSL.versionString()));
                }
            }

            // Disable compression if requested
            if (SSLDisableCompression) {
                boolean disableCompressionSupported = false;
                try {
                    disableCompressionSupported = SSL.hasOp(SSL.SSL_OP_NO_COMPRESSION);
                    if (disableCompressionSupported)
                        SSLContext.setOptions(sslContext, SSL.SSL_OP_NO_COMPRESSION);
                } catch (UnsatisfiedLinkError e) {
                    // Ignore
                }
                if (!disableCompressionSupported) {
                    // OpenSSL does not support ciphers ordering.
                    log.warn(sm.getString("endpoint.warn.noDisableCompression",
                                          SSL.versionString()));
                }
            }

            // List the ciphers that the client is permitted to negotiate
            SSLContext.setCipherSuite(sslContext, SSLCipherSuite);
            // Load Server key and certificate
            SSLContext.setCertificate(sslContext, SSLCertificateFile, SSLCertificateKeyFile, SSLPassword, SSL.SSL_AIDX_RSA);
            // Set certificate chain file
            SSLContext.setCertificateChainFile(sslContext, SSLCertificateChainFile, false);
            // Support Client Certificates
            SSLContext.setCACertificate(sslContext, SSLCACertificateFile, SSLCACertificatePath);
            // Set revocation
            SSLContext.setCARevocation(sslContext, SSLCARevocationFile, SSLCARevocationPath);
            // Client certificate verification
            value = SSL.SSL_CVERIFY_NONE;
            if ("optional".equalsIgnoreCase(SSLVerifyClient)) {
                value = SSL.SSL_CVERIFY_OPTIONAL;
            } else if ("require".equalsIgnoreCase(SSLVerifyClient)) {
                value = SSL.SSL_CVERIFY_REQUIRE;
            } else if ("optionalNoCA".equalsIgnoreCase(SSLVerifyClient)) {
                value = SSL.SSL_CVERIFY_OPTIONAL_NO_CA;
            }
            SSLContext.setVerify(sslContext, value, SSLVerifyDepth);
            // For now, sendfile is not supported with SSL
            useSendfile = false;
        }

        initialized = true;

    }


    /**
     * Start the APR endpoint, creating acceptor, poller and sendfile threads.
     */
    public void start()
        throws Exception {
        // Initialize socket if not done before
        if (!initialized) {
            init();
        }
        if (!running) {
            running = true;
            paused = false;

            // Create worker collection
            if (executor == null) {
                workers = new WorkerStack(maxThreads);
            }

            // Start poller threads
            pollers = new Poller[pollerThreadCount];
            for (int i = 0; i < pollerThreadCount; i++) {
                pollers[i] = new Poller(false);
                pollers[i].init();
                pollers[i].setName(getName() + "-Poller-" + i);
                pollers[i].setPriority(threadPriority);
                pollers[i].setDaemon(true);
                pollers[i].start();
            }

            // Start comet poller threads
            cometPollers = new Poller[pollerThreadCount];
            for (int i = 0; i < pollerThreadCount; i++) {
                cometPollers[i] = new Poller(true);
                cometPollers[i].init();
                cometPollers[i].setName(getName() + "-CometPoller-" + i);
                cometPollers[i].setPriority(threadPriority);
                cometPollers[i].setDaemon(true);
                cometPollers[i].start();
            }

            // Start sendfile threads
            if (useSendfile) {
                sendfiles = new Sendfile[sendfileThreadCount];
                for (int i = 0; i < sendfileThreadCount; i++) {
                    sendfiles[i] = new Sendfile();
                    sendfiles[i].init();
                    sendfiles[i].setName(getName() + "-Sendfile-" + i);
                    sendfiles[i].setPriority(threadPriority);
                    sendfiles[i].setDaemon(true);
                    sendfiles[i].start();
                }
            }

            // Start acceptor threads
            acceptors = new Acceptor[acceptorThreadCount];
            for (int i = 0; i < acceptorThreadCount; i++) {
                acceptors[i] = new Acceptor();
                acceptors[i].setName(getName() + "-Acceptor-" + i);
                acceptors[i].setPriority(threadPriority);
                acceptors[i].setDaemon(getDaemon());
                acceptors[i].start();
            }

        }
    }


    /**
     * Pause the endpoint, which will make it stop accepting new sockets.
     */
    public void pause() {
        if (running && !paused) {
            paused = true;
            unlockAccept();
        }
    }


    /**
     * Resume the endpoint, which will make it start accepting new sockets
     * again.
     */
    public void resume() {
        if (running) {
            paused = false;
        }
    }


    /**
     * Stop the endpoint. This will cause all processing threads to stop.
     */
    public void stop() {
        if (!paused) {
            pause();
        }
        if (running) {
            running = false;
            unlockAccept();
            for (int i = 0; i < acceptors.length; i++) {
                long s = System.currentTimeMillis() + 10000;
                while (acceptors[i].isAlive() && serverSock != 0) {
                    try {
                        acceptors[i].interrupt();
                        acceptors[i].join(1000);
                    } catch (InterruptedException e) {
                        // Ignore
                    }
                    if (System.currentTimeMillis() >= s) {
                        log.warn(sm.getString("endpoint.warn.unlockAcceptorFailed",
                                 acceptors[i].getName()));
                        // If the Acceptor is still running force
                        // the hard socket close.
                        if (serverSock != 0) {
                            Socket.shutdown(serverSock, Socket.APR_SHUTDOWN_READ);
                            serverSock = 0;
                        }
                    }
                }
            }
            for (int i = 0; i < pollers.length; i++) {
                try {
                    pollers[i].destroy();
                } catch (Exception e) {
                    // Ignore
                }
            }
            pollers = null;
            for (int i = 0; i < cometPollers.length; i++) {
                try {
                    cometPollers[i].destroy();
                } catch (Exception e) {
                    // Ignore
                }
            }
            cometPollers = null;
            if (useSendfile) {
                for (int i = 0; i < sendfiles.length; i++) {
                    try {
                        sendfiles[i].destroy();
                    } catch (Exception e) {
                        // Ignore
                    }
                }
                sendfiles = null;
            }
        }
    }


    /**
     * Deallocate APR memory pools, and close server socket.
     */
    public void destroy() throws Exception {
        if (running) {
            stop();
        }
        // Destroy pool if it was initialised
        if (serverSockPool != 0) {
            Pool.destroy(serverSockPool);
            serverSockPool = 0;
        }

        // Close server socket if it was initialised
        if (serverSock != 0) {
            Socket.close(serverSock);
            serverSock = 0;
        }

        sslContext = 0;

        // Close all APR memory pools and resources if initialised
        if (rootPool != 0) {
            Pool.destroy(rootPool);
            rootPool = 0;
        }

        initialized = false;
    }


    // ------------------------------------------------------ Protected Methods


    /**
     * Get a sequence number used for thread naming.
     */
    protected int getSequence() {
        return sequence++;
    }


    /**
     * Unlock the server socket accept using a bugus connection.
     */
    protected void unlockAccept() {
        java.net.Socket s = null;
        InetSocketAddress saddr = null;
        try {
            // Need to create a connection to unlock the accept();
            if (address == null) {
                saddr = new InetSocketAddress("localhost", port);
            } else {
                saddr = new InetSocketAddress(address,port);
            }
            s = new java.net.Socket();
            s.setSoTimeout(soTimeout > 0 ? soTimeout : 60000);
            s.setSoLinger(true ,0);
            if (log.isDebugEnabled()) {
                log.debug("About to unlock socket for: " + saddr);
            }
            s.connect(saddr, unlockTimeout);
            /*
             * In the case of a deferred accept / accept filters we need to
             * send data to wake up the accept. Send OPTIONS * to bypass even
             * BSD accept filters. The Acceptor will discard it.
             */
            if (deferAccept) {
                OutputStreamWriter sw;

                sw = new OutputStreamWriter(s.getOutputStream(), "ISO-8859-1");
                sw.write("OPTIONS * HTTP/1.0\r\n"
                        + "User-Agent: Tomcat wakeup connection\r\n\r\n");
                sw.flush();
            }
        } catch(Exception e) {
            if (log.isDebugEnabled()) {
                log.debug(sm.getString("endpoint.debug.unlock", "" + port), e);
            }
        } finally {
            if (s != null) {
                try {
                    s.close();
                } catch (Exception e) {
                    // Ignore
                }
            }
        }
    }


    /**
     * Process the specified connection.
     */
    protected boolean setSocketOptions(long socket) {
        // Process the connection
        int step = 1;
        try {

            // 1: Set socket options: timeout, linger, etc
            if (soLinger >= 0)
                Socket.optSet(socket, Socket.APR_SO_LINGER, soLinger);
            if (tcpNoDelay)
                Socket.optSet(socket, Socket.APR_TCP_NODELAY, (tcpNoDelay ? 1 : 0));
            if (soTimeout > 0)
                Socket.timeoutSet(socket, soTimeout * 1000);

            // 2: SSL handshake
            step = 2;
            if (sslContext != 0) {
                SSLSocket.attach(sslContext, socket);
                if (SSLSocket.handshake(socket) != 0) {
                    if (log.isDebugEnabled()) {
                        log.debug(sm.getString("endpoint.err.handshake") + ": " + SSL.getLastError());
                    }
                    return false;
                }
            }

        } catch (Throwable t) {
            if (log.isDebugEnabled()) {
                if (step == 2) {
                    log.debug(sm.getString("endpoint.err.handshake"), t);
                } else {
                    log.debug(sm.getString("endpoint.err.unexpected"), t);
                }
            }
            // Tell to close the socket
            return false;
        }
        return true;
    }


    /**
     * Create (or allocate) and return an available processor for use in
     * processing a specific HTTP request, if possible.  If the maximum
     * allowed processors have already been created and are in use, return
     * <code>null</code> instead.
     */
    protected Worker createWorkerThread() {

        synchronized (workers) {
            if (workers.size() > 0) {
                curThreadsBusy++;
                return (workers.pop());
            }
            if ((maxThreads > 0) && (curThreads < maxThreads)) {
                curThreadsBusy++;
                if (curThreadsBusy == maxThreads) {
                    log.info(sm.getString("endpoint.info.maxThreads",
                            Integer.toString(maxThreads), address,
                            Integer.toString(port)));
                }
                return (newWorkerThread());
            } else {
                if (maxThreads < 0) {
                    curThreadsBusy++;
                    return (newWorkerThread());
                } else {
                    return (null);
                }
            }
        }

    }


    /**
     * Create and return a new processor suitable for processing HTTP
     * requests and returning the corresponding responses.
     */
    protected Worker newWorkerThread() {

        Worker workerThread = new Worker();
        workerThread.start();
        return (workerThread);

    }


    /**
     * Return a new worker thread, and block while to worker is available.
     */
    protected Worker getWorkerThread() {
        // Allocate a new worker thread
        synchronized (workers) {
            Worker workerThread;
            while ((workerThread = createWorkerThread()) == null) {
                try {
                    workers.wait();
                } catch (InterruptedException e) {
                    // Ignore
                }
            }
            return workerThread;
        }
    }


    /**
     * Recycle the specified Processor so that it can be used again.
     *
     * @param workerThread The processor to be recycled
     */
    protected void recycleWorkerThread(Worker workerThread) {
        synchronized (workers) {
            workers.push(workerThread);
            curThreadsBusy--;
            workers.notify();
        }
    }


    /**
     * Allocate a new poller of the specified size.
     */
    protected long allocatePoller(int size, long pool, int timeout) {
        try {
            return Poll.create(size, pool, 0, timeout * 1000);
        } catch (Error e) {
            if (Status.APR_STATUS_IS_EINVAL(e.getError())) {
                log.info(sm.getString("endpoint.poll.limitedpollsize", "" + size));
                return 0;
            } else {
                log.error(sm.getString("endpoint.poll.initfail"), e);
                return -1;
            }
        }
    }


    /**
     * Process given socket.
     */
    protected boolean processSocketWithOptions(long socket) {
        try {
            if (executor == null) {
                getWorkerThread().assignWithOptions(socket);
            } else {
                executor.execute(new SocketWithOptionsProcessor(socket));
            }
        } catch (Throwable t) {
            // This means we got an OOM or similar creating a thread, or that
            // the pool and its queue are full
            log.error(sm.getString("endpoint.process.fail"), t);
            return false;
        }
        return true;
    }


    /**
     * Process given socket.
     */
    protected boolean processSocket(long socket) {
        try {
            if (executor == null) {
                getWorkerThread().assign(socket);
            } else {
                executor.execute(new SocketProcessor(socket));
            }
        } catch (Throwable t) {
            // This means we got an OOM or similar creating a thread, or that
            // the pool and its queue are full
            log.error(sm.getString("endpoint.process.fail"), t);
            return false;
        }
        return true;
    }


    /**
     * Process given socket for an event.
     */
    protected boolean processSocket(long socket, SocketStatus status) {
        try {
            if (executor == null) {
                getWorkerThread().assign(socket, status);
            } else {
                executor.execute(new SocketEventProcessor(socket, status));
            }
        } catch (Throwable t) {
            // This means we got an OOM or similar creating a thread, or that
            // the pool and its queue are full
            log.error(sm.getString("endpoint.process.fail"), t);
            return false;
        }
        return true;
    }

    private void destroySocket(long socket) {
        if (running && socket != 0) {
            // If not running the socket will be destroyed by
            // parent pool or acceptor socket.
            // In any case disable double free which would cause JVM core.
            Socket.destroy(socket);
        }
    }


    // --------------------------------------------------- Acceptor Inner Class


    /**
     * Server socket acceptor thread.
     */
    protected class Acceptor extends Thread {

        private final Log log = LogFactory.getLog(AprEndpoint.Acceptor.class);

        /**
         * The background thread that listens for incoming TCP/IP connections and
         * hands them off to an appropriate processor.
         */
        public void run() {

            // Loop until we receive a shutdown command
            while (running) {

                // Loop if endpoint is paused
                while (paused && running) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        // Ignore
                    }
                }

                if (!running) {
                    break;
                }
                try {
                    // Accept the next incoming connection from the server socket
                    long socket = Socket.accept(serverSock);
                    /*
                     * In the case of a deferred accept unlockAccept needs to
                     * send data. This data will be rubbish, so destroy the
                     * socket and don't process it.
                     */
                    if (deferAccept && (paused || !running)) {
                        destroySocket(socket);
                        continue;
                    }
                    // Hand this socket off to an appropriate processor
                    if (!processSocketWithOptions(socket)) {
                        // Close socket and pool right away
                        destroySocket(socket);
                    }
                } catch (Throwable t) {
                    if (running) {
                        String msg = sm.getString("endpoint.accept.fail");
                        if (t instanceof Error) {
                            Error e = (Error) t;
                            if (e.getError() == 233) {
                                // Not an error on HP-UX so log as a warning
                                // so it can be filtered out on that platform
                                // See bug 50273
                                log.warn(msg, t);
                            } else {
                                log.error(msg, t);
                            }
                        } else {
                                log.error(msg, t);
                        }
                    }
                }

                // The processor will recycle itself when it finishes

            }

        }

    }


    // ----------------------------------------------------- Poller Inner Class


    /**
     * Poller class.
     */
    public class Poller extends Thread {

        protected long serverPollset = 0;
        protected long pool = 0;
        protected long[] desc;

        protected long[] addS;
        protected volatile int addCount = 0;

        protected boolean comet = true;

        protected volatile int keepAliveCount = 0;
        public int getKeepAliveCount() { return keepAliveCount; }

        public Poller(boolean comet) {
            this.comet = comet;
        }

        /**
         * Create the poller. With some versions of APR, the maximum poller size will
         * be 62 (recompiling APR is necessary to remove this limitation).
         */
        protected void init() {
            pool = Pool.create(serverSockPool);
            int size = pollerSize / pollerThreadCount;
            int timeout = keepAliveTimeout;
            if (timeout < 0) {
                timeout = soTimeout;
            }
            serverPollset = allocatePoller(size, pool, timeout);
            if (serverPollset == 0 && size > 1024) {
                size = 1024;
                serverPollset = allocatePoller(size, pool, timeout);
            }
            if (serverPollset == 0) {
                size = 62;
                serverPollset = allocatePoller(size, pool, timeout);
            }
            desc = new long[size * 2];
            keepAliveCount = 0;
            addS = new long[size];
            addCount = 0;
        }

        /**
         * Destroy the poller.
         */
        public void destroy() {
            // Close all sockets in the add queue
            for (int i = 0; i < addCount; i++) {
                if (comet) {
                    processSocket(addS[i], SocketStatus.STOP);
                } else {
                    destroySocket(addS[i]);
                }
            }
            // Close all sockets still in the poller
            int rv = Poll.pollset(serverPollset, desc);
            if (rv > 0) {
                for (int n = 0; n < rv; n++) {
                    if (comet) {
                        processSocket(desc[n*2+1], SocketStatus.STOP);
                    } else {
                        destroySocket(desc[n*2+1]);
                    }
                }
            }
            Pool.destroy(pool);
            keepAliveCount = 0;
            addCount = 0;
            try {
                while (this.isAlive()) {
                    this.interrupt();
                    this.join(1000);
                }
            } catch (InterruptedException e) {
                // Ignore
            }
        }

        /**
         * Add specified socket and associated pool to the poller. The socket will
         * be added to a temporary array, and polled first after a maximum amount
         * of time equal to pollTime (in most cases, latency will be much lower,
         * however).
         *
         * @param socket to add to the poller
         */
        public void add(long socket) {
            synchronized (this) {
                // Add socket to the list. Newly added sockets will wait
                // at most for pollTime before being polled
                if (addCount >= addS.length) {
                    // Can't do anything: close the socket right away
                    if (comet) {
                        processSocket(socket, SocketStatus.ERROR);
                    } else {
                        destroySocket(socket);
                    }
                    return;
                }
                addS[addCount] = socket;
                addCount++;
                this.notify();
            }
        }

        /**
         * The background thread that listens for incoming TCP/IP connections and
         * hands them off to an appropriate processor.
         */
        public void run() {

            long maintainTime = 0;
            // Loop until we receive a shutdown command
            while (running) {
                // Loop if endpoint is paused
                while (paused && running) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        // Ignore
                    }
                }

                if (!running) {
                    break;
                }
                if (keepAliveCount < 1 && addCount < 1) {
                    synchronized (this) {
                        while (keepAliveCount < 1 && addCount < 1 && running) {
                            // Reset maintain time.
                            maintainTime = 0;
                            try {
                                this.wait();
                            } catch (InterruptedException e) {
                                // Ignore
                            }
                        }
                    }
                }

                if (!running) {
                    break;
                }
                try {
                    // Add sockets which are waiting to the poller
                    if (addCount > 0) {
                        synchronized (this) {
                            int successCount = 0;
                            try {
                                for (int i = (addCount - 1); i >= 0; i--) {
                                    int rv = Poll.add
                                        (serverPollset, addS[i], Poll.APR_POLLIN);
                                    if (rv == Status.APR_SUCCESS) {
                                        successCount++;
                                    } else {
                                        // Can't do anything: close the socket right away
                                        if (comet) {
                                            processSocket(addS[i], SocketStatus.ERROR);
                                        } else {
                                            destroySocket(addS[i]);
                                        }
                                    }
                                }
                            } finally {
                                keepAliveCount += successCount;
                                addCount = 0;
                            }
                        }
                    }

                    maintainTime += pollTime;
                    // Pool for the specified interval
                    int rv = Poll.poll(serverPollset, pollTime, desc, true);
                    if (rv > 0) {
                        keepAliveCount -= rv;
                        for (int n = 0; n < rv; n++) {
                            // Check for failed sockets and hand this socket off to a worker
                            if (((desc[n*2] & Poll.APR_POLLHUP) == Poll.APR_POLLHUP)
                                    || ((desc[n*2] & Poll.APR_POLLERR) == Poll.APR_POLLERR)
                                    || (comet && (!processSocket(desc[n*2+1], SocketStatus.OPEN)))
                                    || (!comet && (!processSocket(desc[n*2+1])))) {
                                // Close socket and clear pool
                                if (comet) {
                                    processSocket(desc[n*2+1], SocketStatus.DISCONNECT);
                                } else {
                                    destroySocket(desc[n*2+1]);
                                }
                                continue;
                            }
                        }
                    } else if (rv < 0) {
                        int errn = -rv;
                        /* Any non timeup or interrupted error is critical */
                        if ((errn != Status.TIMEUP) && (errn != Status.EINTR)) {
                            if (errn >  Status.APR_OS_START_USERERR) {
                                errn -=  Status.APR_OS_START_USERERR;
                            }
                            log.error(sm.getString("endpoint.poll.fail", "" + errn, Error.strerror(errn)));
                            // Handle poll critical failure
                            synchronized (this) {
                                destroy();
                                init();
                            }
                            continue;
                        }
                    }
                    if (soTimeout > 0 && maintainTime > 1000000L && running) {
                        rv = Poll.maintain(serverPollset, desc, true);
                        maintainTime = 0;
                        if (rv > 0) {
                            keepAliveCount -= rv;
                            for (int n = 0; n < rv; n++) {
                                // Close socket and clear pool
                                if (comet) {
                                    processSocket(desc[n], SocketStatus.TIMEOUT);
                                } else {
                                    destroySocket(desc[n]);
                                }
                            }
                        }
                    }
                } catch (Throwable t) {
                    log.error(sm.getString("endpoint.poll.error"), t);
                }

            }

            synchronized (this) {
                this.notifyAll();
            }
        }
    }


    // ----------------------------------------------------- Worker Inner Class


    /**
     * Server processor class.
     */
    protected class Worker implements Runnable {


        protected Thread thread = null;
        protected boolean available = false;
        protected long socket = 0;
        protected SocketStatus status = null;
        protected boolean options = false;


        /**
         * Process an incoming TCP/IP connection on the specified socket.  Any
         * exception that occurs during processing must be logged and swallowed.
         * <b>NOTE</b>:  This method is called from our Connector's thread.  We
         * must assign it to our own thread so that multiple simultaneous
         * requests can be handled.
         *
         * @param socket TCP socket to process
         */
        protected synchronized void assignWithOptions(long socket) {

            // Wait for the Processor to get the previous Socket
            while (available) {
                try {
                    wait();
                } catch (InterruptedException e) {
                }
            }

            // Store the newly available Socket and notify our thread
            this.socket = socket;
            status = null;
            options = true;
            available = true;
            notifyAll();

        }


        /**
         * Process an incoming TCP/IP connection on the specified socket.  Any
         * exception that occurs during processing must be logged and swallowed.
         * <b>NOTE</b>:  This method is called from our Connector's thread.  We
         * must assign it to our own thread so that multiple simultaneous
         * requests can be handled.
         *
         * @param socket TCP socket to process
         */
        protected synchronized void assign(long socket) {

            // Wait for the Processor to get the previous Socket
            while (available) {
                try {
                    wait();
                } catch (InterruptedException e) {
                }
            }

            // Store the newly available Socket and notify our thread
            this.socket = socket;
            status = null;
            options = false;
            available = true;
            notifyAll();

        }


        protected synchronized void assign(long socket, SocketStatus status) {

            // Wait for the Processor to get the previous Socket
            while (available) {
                try {
                    wait();
                } catch (InterruptedException e) {
                }
            }

            // Store the newly available Socket and notify our thread
            this.socket = socket;
            this.status = status;
            options = false;
            available = true;
            notifyAll();

        }


        /**
         * Await a newly assigned Socket from our Connector, or <code>null</code>
         * if we are supposed to shut down.
         */
        protected synchronized long await() {

            // Wait for the Connector to provide a new Socket
            while (!available) {
                try {
                    wait();
                } catch (InterruptedException e) {
                }
            }

            // Notify the Connector that we have received this Socket
            long socket = this.socket;
            available = false;
            notifyAll();

            return (socket);

        }


        /**
         * The background thread that listens for incoming TCP/IP connections and
         * hands them off to an appropriate processor.
         */
        public void run() {

            // Process requests until we receive a shutdown signal
            while (running) {

                // Wait for the next socket to be assigned
                long socket = await();
                if (socket == 0)
                    continue;

                if (!deferAccept && options) {
                    if (setSocketOptions(socket)) {
                        getPoller().add(socket);
                    } else {
                        // Close socket and pool
                        destroySocket(socket);
                        socket = 0;
                    }
                } else {

                    // Process the request from this socket
                    if ((status != null) && (handler.event(socket, status) == Handler.SocketState.CLOSED)) {
                        // Close socket and pool
                        destroySocket(socket);
                        socket = 0;
                    } else if ((status == null) && ((options && !setSocketOptions(socket))
                            || handler.process(socket) == Handler.SocketState.CLOSED)) {
                        // Close socket and pool
                        destroySocket(socket);
                        socket = 0;
                    }
                }

                // Finish up this request
                recycleWorkerThread(this);

            }

        }


        /**
         * Start the background processing thread.
         */
        public void start() {
            thread = new Thread(this);
            thread.setName(getName() + "-" + (++curThreads));
            thread.setDaemon(true);
            thread.start();
        }


    }


    // ----------------------------------------------- SendfileData Inner Class


    /**
     * SendfileData class.
     */
    public static class SendfileData {
        // File
        public String fileName;
        public long fd;
        public long fdpool;
        // Range information
        public long start;
        public long end;
        // Socket and socket pool
        public long socket;
        // Position
        public long pos;
        // KeepAlive flag
        public boolean keepAlive;
    }


    // --------------------------------------------------- Sendfile Inner Class


    /**
     * Sendfile class.
     */
    public class Sendfile extends Thread {

        protected long sendfilePollset = 0;
        protected long pool = 0;
        protected long[] desc;
        protected HashMap<Long, SendfileData> sendfileData;

        protected volatile int sendfileCount;
        public int getSendfileCount() { return sendfileCount; }

        protected ArrayList<SendfileData> addS;
        protected volatile int addCount;

        /**
         * Create the sendfile poller. With some versions of APR, the maximum poller size will
         * be 62 (reocmpiling APR is necessary to remove this limitation).
         */
        protected void init() {
            pool = Pool.create(serverSockPool);
            int size = sendfileSize / sendfileThreadCount;
            sendfilePollset = allocatePoller(size, pool, soTimeout);
            if (sendfilePollset == 0 && size > 1024) {
                size = 1024;
                sendfilePollset = allocatePoller(size, pool, soTimeout);
            }
            if (sendfilePollset == 0) {
                size = 62;
                sendfilePollset = allocatePoller(size, pool, soTimeout);
            }
            desc = new long[size * 2];
            sendfileData = new HashMap<Long, SendfileData>(size);
            addS = new ArrayList<SendfileData>();
            addCount = 0;
        }

        /**
         * Destroy the poller.
         */
        public void destroy() {
            // Close any socket remaining in the add queue
            addCount = 0;
            for (int i = (addS.size() - 1); i >= 0; i--) {
                SendfileData data = addS.get(i);
                destroySocket(data.socket);
            }
            addS.clear();
            // Close all sockets still in the poller
            int rv = Poll.pollset(sendfilePollset, desc);
            if (rv > 0) {
                for (int n = 0; n < rv; n++) {
                    destroySocket(desc[n*2+1]);
                }
            }
            Pool.destroy(pool);
            sendfileData.clear();
            try {
                while (this.isAlive()) {
                    this.interrupt();
                    this.join(1000);
                }
            } catch (InterruptedException e) {
                // Ignore
            }
        }

        /**
         * Add the sendfile data to the sendfile poller. Note that in most cases,
         * the initial non blocking calls to sendfile will return right away, and
         * will be handled asynchronously inside the kernel. As a result,
         * the poller will never be used.
         *
         * @param data containing the reference to the data which should be snet
         * @return true if all the data has been sent right away, and false
         *              otherwise
         */
        public boolean add(SendfileData data) {
            // Initialize fd from data given
            try {
                data.fdpool = Socket.pool(data.socket);
                data.fd = File.open
                    (data.fileName, File.APR_FOPEN_READ
                     | File.APR_FOPEN_SENDFILE_ENABLED | File.APR_FOPEN_BINARY,
                     0, data.fdpool);
                data.pos = data.start;
                // Set the socket to nonblocking mode
                Socket.timeoutSet(data.socket, 0);
                while (true) {
                    long nw = Socket.sendfilen(data.socket, data.fd,
                                               data.pos, data.end - data.pos, 0);
                    if (nw < 0) {
                        if (!(-nw == Status.EAGAIN)) {
                            Pool.destroy(data.fdpool);
                            // No need to close socket, this will be done by
                            // calling code since data.socket == 0
                            data.socket = 0;
                            return false;
                        } else {
                            // Break the loop and add the socket to poller.
                            break;
                        }
                    } else {
                        data.pos = data.pos + nw;
                        if (data.pos >= data.end) {
                            // Entire file has been sent
                            Pool.destroy(data.fdpool);
                            // Set back socket to blocking mode
                            Socket.timeoutSet(data.socket, soTimeout * 1000);
                            return true;
                        }
                    }
                }
            } catch (Exception e) {
                log.error(sm.getString("endpoint.sendfile.error"), e);
                return false;
            }
            // Add socket to the list. Newly added sockets will wait
            // at most for pollTime before being polled
            synchronized (this) {
                addS.add(data);
                addCount++;
                this.notify();
            }
            return false;
        }

        /**
         * Remove socket from the poller.
         *
         * @param data the sendfile data which should be removed
         */
        protected void remove(SendfileData data) {
            int rv = Poll.remove(sendfilePollset, data.socket);
            if (rv == Status.APR_SUCCESS) {
                sendfileCount--;
            }
            sendfileData.remove(new Long(data.socket));
        }

        /**
         * The background thread that listens for incoming TCP/IP connections and
         * hands them off to an appropriate processor.
         */
        public void run() {

            long maintainTime = 0;
            // Loop until we receive a shutdown command
            while (running) {

                // Loop if endpoint is paused
                while (paused && running) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        // Ignore
                    }
                }

                if (!running) {
                    break;
                }
                if (sendfileCount < 1 && addCount < 1) {
                    synchronized (this) {
                        while (sendfileCount < 1 && addS.size() < 1 && running) {
                            // Reset maintain time.
                            maintainTime = 0;
                            try {
                                this.wait();
                            } catch (InterruptedException e) {
                                // Ignore
                            }
                        }
                    }
                }

                if (!running) {
                    break;
                }
                try {
                    // Add socket to the poller
                    if (addCount > 0) {
                        synchronized (this) {
                            int successCount = 0;
                            try {
                                for (int i = (addS.size() - 1); i >= 0; i--) {
                                    SendfileData data = addS.get(i);
                                    int rv = Poll.add(sendfilePollset, data.socket, Poll.APR_POLLOUT);
                                    if (rv == Status.APR_SUCCESS) {
                                        sendfileData.put(new Long(data.socket), data);
                                        successCount++;
                                    } else {
                                        log.warn(sm.getString("endpoint.sendfile.addfail", "" + rv, Error.strerror(rv)));
                                        // Can't do anything: close the socket right away
                                        destroySocket(data.socket);
                                    }
                                }
                            } finally {
                                sendfileCount += successCount;
                                addS.clear();
                                addCount = 0;
                            }
                        }
                    }

                    maintainTime += pollTime;
                    // Pool for the specified interval
                    int rv = Poll.poll(sendfilePollset, pollTime, desc, false);
                    if (rv > 0) {
                        for (int n = 0; n < rv; n++) {
                            // Get the sendfile state
                            SendfileData state =
                                sendfileData.get(new Long(desc[n*2+1]));
                            // Problem events
                            if (((desc[n*2] & Poll.APR_POLLHUP) == Poll.APR_POLLHUP)
                                    || ((desc[n*2] & Poll.APR_POLLERR) == Poll.APR_POLLERR)) {
                                // Close socket and clear pool
                                remove(state);
                                // Destroy file descriptor pool, which should close the file
                                // Close the socket, as the reponse would be incomplete
                                destroySocket(state.socket);
                                continue;
                            }
                            // Write some data using sendfile
                            long nw = Socket.sendfilen(state.socket, state.fd,
                                                       state.pos,
                                                       state.end - state.pos, 0);
                            if (nw < 0) {
                                // Close socket and clear pool
                                remove(state);
                                // Close the socket, as the reponse would be incomplete
                                // This will close the file too.
                                destroySocket(state.socket);
                                continue;
                            }

                            state.pos = state.pos + nw;
                            if (state.pos >= state.end) {
                                remove(state);
                                if (state.keepAlive) {
                                    // Destroy file descriptor pool, which should close the file
                                    Pool.destroy(state.fdpool);
                                    Socket.timeoutSet(state.socket, soTimeout * 1000);
                                    // If all done put the socket back in the poller for
                                    // processing of further requests
                                    getPoller().add(state.socket);
                                } else {
                                    // Close the socket since this is
                                    // the end of not keep-alive request.
                                    destroySocket(state.socket);
                                }
                            }
                        }
                    } else if (rv < 0) {
                        int errn = -rv;
                        /* Any non timeup or interrupted error is critical */
                        if ((errn != Status.TIMEUP) && (errn != Status.EINTR)) {
                            if (errn >  Status.APR_OS_START_USERERR) {
                                errn -=  Status.APR_OS_START_USERERR;
                            }
                            log.error(sm.getString("endpoint.poll.fail", "" + errn, Error.strerror(errn)));
                            // Handle poll critical failure
                            synchronized (this) {
                                destroy();
                                init();
                            }
                            continue;
                        }
                    }
                    // Call maintain for the sendfile poller
                    if (soTimeout > 0 && maintainTime > 1000000L && running) {
                        rv = Poll.maintain(sendfilePollset, desc, true);
                        maintainTime = 0;
                        if (rv > 0) {
                            for (int n = 0; n < rv; n++) {
                                // Get the sendfile state
                                SendfileData state = sendfileData.get(new Long(desc[n]));
                                // Close socket and clear pool
                                remove(state);
                                // Destroy file descriptor pool, which should close the file
                                // Close the socket, as the response would be incomplete
                                destroySocket(state.socket);
                            }
                        }
                    }
                } catch (Throwable t) {
                    log.error(sm.getString("endpoint.poll.error"), t);
                }
            }

            synchronized (this) {
                this.notifyAll();
            }

        }

    }


    // ------------------------------------------------ Handler Inner Interface


    /**
     * Bare bones interface used for socket processing. Per thread data is to be
     * stored in the ThreadWithAttributes extra folders, or alternately in
     * thread local fields.
     */
    public interface Handler {
        public enum SocketState {
            OPEN, CLOSED, LONG
        }
        public SocketState process(long socket);
        public SocketState event(long socket, SocketStatus status);
    }


    // ------------------------------------------------- WorkerStack Inner Class


    public class WorkerStack {

        protected Worker[] workers = null;
        protected int end = 0;

        public WorkerStack(int size) {
            workers = new Worker[size];
        }

        /**
         * Put the worker into the queue. If the queue is full (for example if
         * the queue has been reduced in size) the worker will be dropped.
         *
         * @param   worker  the worker to be appended to the queue (first
         *                  element).
         */
        public void push(Worker worker) {
            if (end < workers.length) {
                workers[end++] = worker;
            } else {
                curThreads--;
            }
        }

        /**
         * Get the first object out of the queue. Return null if the queue
         * is empty.
         */
        public Worker pop() {
            if (end > 0) {
                return workers[--end];
            }
            return null;
        }

        /**
         * Get the first object out of the queue, Return null if the queue
         * is empty.
         */
        public Worker peek() {
            return workers[end];
        }

        /**
         * Is the queue empty?
         */
        public boolean isEmpty() {
            return (end == 0);
        }

        /**
         * How many elements are there in this queue?
         */
        public int size() {
            return (end);
        }

        /**
         * Resize the queue. If there are too many objects in the queue for the
         * new size, drop the excess.
         *
         * @param newSize
         */
        public void resize(int newSize) {
            Worker[] newWorkers = new Worker[newSize];
            int len = workers.length;
            if (newSize < len) {
                len = newSize;
            }
            System.arraycopy(workers, 0, newWorkers, 0, len);
            workers = newWorkers;
        }
    }


    // ---------------------------------------------- SocketProcessor Inner Class


    /**
     * This class is the equivalent of the Worker, but will simply use in an
     * external Executor thread pool. This will also set the socket options
     * and do the handshake.
     */
    protected class SocketWithOptionsProcessor implements Runnable {

        protected long socket = 0;

        public SocketWithOptionsProcessor(long socket) {
            this.socket = socket;
        }

        public void run() {

            if (!deferAccept) {
                if (setSocketOptions(socket)) {
                    getPoller().add(socket);
                } else {
                    // Close socket and pool
                    destroySocket(socket);
                    socket = 0;
                }
            } else {
                // Process the request from this socket
                if (!setSocketOptions(socket)
                        || handler.process(socket) == Handler.SocketState.CLOSED) {
                    // Close socket and pool
                    destroySocket(socket);
                    socket = 0;
                }
            }

        }

    }


    // ---------------------------------------------- SocketProcessor Inner Class


    /**
     * This class is the equivalent of the Worker, but will simply use in an
     * external Executor thread pool.
     */
    protected class SocketProcessor implements Runnable {

        protected long socket = 0;

        public SocketProcessor(long socket) {
            this.socket = socket;
        }

        public void run() {

            // Process the request from this socket
            if (handler.process(socket) == Handler.SocketState.CLOSED) {
                // Close socket and pool
                destroySocket(socket);
                socket = 0;
            }

        }

    }


    // --------------------------------------- SocketEventProcessor Inner Class


    /**
     * This class is the equivalent of the Worker, but will simply use in an
     * external Executor thread pool.
     */
    protected class SocketEventProcessor implements Runnable {

        protected long socket = 0;
        protected SocketStatus status = null;

        public SocketEventProcessor(long socket, SocketStatus status) {
            this.socket = socket;
            this.status = status;
        }

        public void run() {

            // Process the request from this socket
            if (handler.event(socket, status) == Handler.SocketState.CLOSED) {
                // Close socket and pool
                destroySocket(socket);
                socket = 0;
            }

        }

    }


}
