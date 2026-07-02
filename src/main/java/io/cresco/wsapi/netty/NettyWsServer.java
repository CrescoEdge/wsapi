package io.cresco.wsapi.netty;

import io.cresco.library.plugin.PluginBuilder;
import io.cresco.library.utilities.CLogger;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;

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
        return SslContextBuilder.forServer(kmf)
                .protocols("TLSv1.3", "TLSv1.2")
                .build();
    }

    public void start() throws Exception {
        final SslContext sslContext = buildServerSslContext();

        // Virtual-thread-backed accept/IO would need custom IoHandler; NIO event loops are fine and
        // the per-message work is offloaded where blocking (broker publish is non-blocking on vm://).
        bossGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        workerGroup = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());

        ServerBootstrap b = new ServerBootstrap();
        b.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.SO_RCVBUF, 4 * 1024 * 1024)
                .childOption(ChannelOption.SO_SNDBUF, 4 * 1024 * 1024)
                .childOption(ChannelOption.TCP_NODELAY, true)
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
