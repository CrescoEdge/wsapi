package io.cresco.wsapi.netty;

import io.cresco.library.plugin.PluginBuilder;
import io.cresco.library.utilities.CLogger;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.AdaptiveRecvByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.ssl.OpenSsl;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.SslProvider;

import javax.net.ssl.KeyManagerFactory;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.concurrent.Executors;

/**
 * Netty replacement for the embedded Jetty 12 wsapi server. One TLS (wss) listener that
 * serves all three Cresco WebSocket endpoints ({@code /api/dataplane}, {@code /api/apisocket},
 * {@code /api/logstreamer}) with {@code cresco_service_key} header auth on the HTTP upgrade.
 *
 * Pipeline per connection: SslHandler -> HttpServerCodec -> HttpObjectAggregator -> WsRouter.
 * The router validates the auth header, then (per path) reconfigures the pipeline with a
 * WebSocketServerProtocolHandler + frame aggregator + the endpoint handler and lets the upgrade
 * proceed. Uses the same self-signed keystore Plugin already mints.
 */
public class NettyWsServer {

    private final PluginBuilder plugin;
    private final CLogger logger;
    private final int port;
    private final Path keyStorePath;
    private final char[] keyStorePassword;
    private final int ioBufferBytes;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    public NettyWsServer(PluginBuilder plugin, int port, Path keyStorePath, char[] keyStorePassword, int ioBufferBytes) {
        this.plugin = plugin;
        this.logger = plugin.getLogger(NettyWsServer.class.getName(), CLogger.Level.Info);
        this.port = port;
        this.keyStorePath = keyStorePath;
        this.keyStorePassword = keyStorePassword;
        this.ioBufferBytes = ioBufferBytes;
    }

    private SslContext buildServerSslContext() throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        // the wsapi keystore is written by Plugin.generateCertChainKeyStore as PKCS12
        try (java.io.InputStream in = java.nio.file.Files.newInputStream(keyStorePath)) {
            ks.load(in, keyStorePassword);
        } catch (Exception primary) {
            // fall back to JKS if the store isn't PKCS12
            ks = KeyStore.getInstance("JKS");
            try (java.io.InputStream in = java.nio.file.Files.newInputStream(keyStorePath)) {
                ks.load(in, keyStorePassword);
            }
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, keyStorePassword);

        // TLS provider: default JDK (SSLEngine). Set wsapi_ssl_provider=OPENSSL to use the native
        // BoringSSL provider (netty-tcnative) -- ~2-3x faster bulk crypto, the single-stream ceiling.
        // Falls back to JDK (with a warning) if OPENSSL is requested but the native lib did not load,
        // so a misconfig or a platform without the native classifier never breaks the server.
        SslProvider provider = SslProvider.JDK;
        String requested = plugin.getConfig().getStringParam("wsapi_ssl_provider", "JDK");
        if ("OPENSSL".equalsIgnoreCase(requested)) {
            if (OpenSsl.isAvailable()) {
                provider = SslProvider.OPENSSL;
                logger.info("wsapi TLS provider: OPENSSL (native BoringSSL)");
            } else {
                logger.warn("wsapi_ssl_provider=OPENSSL requested but native lib unavailable ("
                        + OpenSsl.unavailabilityCause() + "); falling back to JDK");
            }
        } else {
            logger.info("wsapi TLS provider: JDK");
        }
        return SslContextBuilder.forServer(kmf)
                .sslProvider(provider)
                .protocols("TLSv1.3", "TLSv1.2")
                .build();
    }

    public void start() throws Exception {
        final SslContext sslContext = buildServerSslContext();

        // Virtual-thread-backed accept/IO would need custom IoHandler; NIO event loops are fine and
        // the per-message work is offloaded where blocking (broker publish is non-blocking on vm://).
        bossGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        workerGroup = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());

        // Read/socket tuning (configurable; defaults preserve behavior). Default Netty adaptive
        // reads cap at 64KB -> the SslHandler decrypts only ~4 TLS records per socket read, adding
        // syscall/overhead on a bulk stream. Enlarge the max read so each read pulls a full
        // large-message worth of ciphertext, and bound the outbound buffer for egress backpressure.
        int readMax = plugin.getConfig().getIntegerParam("wsapi_read_chunk_bytes", 256 * 1024);
        int sockBuf = plugin.getConfig().getIntegerParam("wsapi_socket_buffer_bytes", 4 * 1024 * 1024);
        int writeHigh = plugin.getConfig().getIntegerParam("wsapi_write_high_water_bytes", 2 * 1024 * 1024);

        ServerBootstrap b = new ServerBootstrap();
        b.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.SO_RCVBUF, sockBuf)
                .childOption(ChannelOption.SO_SNDBUF, sockBuf)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.RCVBUF_ALLOCATOR, new AdaptiveRecvByteBufAllocator(2048, 65536, readMax))
                .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(writeHigh / 2, writeHigh))
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline p = ch.pipeline();
                        SslHandler ssl = sslContext.newHandler(ch.alloc());
                        p.addLast("ssl", ssl);
                        p.addLast("http", new HttpServerCodec());
                        // aggregate the HTTP upgrade request (small); WS frames handled after upgrade
                        p.addLast("aggregator", new HttpObjectAggregator(65536));
                        p.addLast("router", new WsRouter(plugin, ioBufferBytes));
                    }
                });

        serverChannel = b.bind(port).sync().channel();
        logger.info("wsapi Netty WebSocket server started on wss port " + port);
    }

    public void stop() {
        try { if (serverChannel != null) serverChannel.close().sync(); } catch (Exception ignore) {}
        if (bossGroup != null) bossGroup.shutdownGracefully();
        if (workerGroup != null) workerGroup.shutdownGracefully();
        logger.info("wsapi Netty WebSocket server stopped");
    }
}
