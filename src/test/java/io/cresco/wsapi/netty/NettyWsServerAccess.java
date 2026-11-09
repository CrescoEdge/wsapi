package io.cresco.wsapi.netty;

/** Test access to NettyWsServer's package-private TLS choices. */
public final class NettyWsServerAccess {
    private NettyWsServerAccess() {}

    public static String[] protocols(boolean fips) { return NettyWsServer.protocols(fips); }
}
