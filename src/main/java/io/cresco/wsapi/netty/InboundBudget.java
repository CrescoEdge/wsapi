package io.cresco.wsapi.netty;

import io.cresco.wsapi.auth.Authenticator;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.util.ReferenceCountUtil;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounds the inbound message bytes wsapi holds in memory: per caller (all its connections together) and in total. A
 * message is buffered whole before it is handled (up to its endpoint's cap, 64 MiB by default), so one user with 30
 * connections each sending a 60 MiB frame slowly held 1.8 GB of direct memory.
 *
 * <p>Two handlers per connection share one account: {@link #counter()} sits right after TLS and counts every decrypted
 * byte as it arrives (before the WebSocket decoder and the frame aggregator buffer it); {@link #release()} sits after the
 * aggregator and, once a whole message has been handled, gives the connection's bytes back. A read that would take the
 * caller over {@code wsapi_max_inflight_bytes_per_user}, or everyone over {@code wsapi_max_inflight_bytes}, closes that
 * connection with WebSocket status 1013 (try again later) and a reason naming the setting; nothing it sent is handled.
 * (What remains held after a message is at most one read buffer, which the next message's release covers.)
 */
public final class InboundBudget {

    /** Default bound per caller (all its connections together). */
    public static final long DEFAULT_PER_CALLER = 256L << 20;

    /** Connections closed because a bound was reached, since start. */
    public static final AtomicLong CLOSED = new AtomicLong();

    private volatile long perCaller = DEFAULT_PER_CALLER;
    private volatile long total = defaultTotal();
    private final AtomicLong inflight = new AtomicLong();
    private final Map<String, AtomicLong> byCaller = new ConcurrentHashMap<>();

    /** Default total bound: half of the direct memory Netty may use, at most 2 GiB, at least 256 MiB. */
    public static long defaultTotal() {
        long direct = io.netty.util.internal.PlatformDependent.maxDirectMemory();
        return Math.max(256L << 20, Math.min(2L << 30, direct / 2));
    }

    /** Set the bounds; each at least 1 MiB, and the per-caller bound at most the total. */
    public InboundBudget bounds(long perCaller, long total) {
        this.total = Math.max(1L << 20, total);
        this.perCaller = Math.max(1L << 20, Math.min(this.total, perCaller));
        return this;
    }

    public long perCaller() { return perCaller; }

    public long total() { return total; }

    /** Inbound message bytes held now, by everyone. */
    public long inflight() { return inflight.get(); }

    long inflight(Authenticator.Principal p) {
        AtomicLong n = byCaller.get(p.key());
        return n == null ? 0 : n.get();
    }

    /** One connection's account. */
    final class Account {
        private final String caller;
        private long held;                    // event loop only
        private boolean refused;

        Account(Authenticator.Principal p) { this.caller = p.key(); }

        /** Count {@code n} bytes; null when within the bounds, else the setting that was exceeded (nothing counted). */
        String take(long n) {
            AtomicLong mine = byCaller.computeIfAbsent(caller, k -> new AtomicLong());
            if (mine.addAndGet(n) > perCaller) {
                mine.addAndGet(-n);
                return "wsapi_max_inflight_bytes_per_user";
            }
            if (inflight.addAndGet(n) > total) {
                inflight.addAndGet(-n);
                mine.addAndGet(-n);
                return "wsapi_max_inflight_bytes";
            }
            held += n;
            return null;
        }

        void giveBack() {
            if (held == 0) return;
            long n = held;
            held = 0;
            inflight.addAndGet(-n);
            byCaller.computeIfPresent(caller, (k, v) -> v.addAndGet(-n) <= 0 ? null : v);
        }
    }

    /** The two handlers of one connection: {@code [counter, release]}. */
    ChannelInboundHandlerAdapter[] handlers(Authenticator.Principal p) {
        Account a = new Account(p);
        return new ChannelInboundHandlerAdapter[]{new Counter(a), new Release(a)};
    }

    /** Counts decrypted bytes as they arrive; over a bound, closes the connection (1013) and drops what it sent. */
    private final class Counter extends ChannelInboundHandlerAdapter {
        private final Account a;

        Counter(Account a) { this.a = a; }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (a.refused) {
                ReferenceCountUtil.release(msg);
                return;
            }
            if (msg instanceof ByteBuf) {
                String over = a.take(((ByteBuf) msg).readableBytes());
                if (over != null) {
                    a.refused = true;
                    ReferenceCountUtil.release(msg);
                    CLOSED.incrementAndGet();
                    refuse(ctx.channel(), over);
                    return;
                }
            }
            ctx.fireChannelRead(msg);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            a.giveBack();
            super.channelInactive(ctx);
        }
    }

    /** After the aggregator: a whole message was handled (synchronously, by the endpoint); its bytes are free again. */
    private static final class Release extends ChannelInboundHandlerAdapter {
        private final Account a;

        Release(Account a) { this.a = a; }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            try {
                ctx.fireChannelRead(msg);
            } finally {
                a.giveBack();
            }
        }
    }

    /** Close with 1013 (try again later), the reason naming the bound; written from the tail, through the frame encoder. */
    private void refuse(Channel ch, String setting) {
        String why = "too many message bytes in flight (" + setting + "=" + ("wsapi_max_inflight_bytes".equals(setting) ? total : perCaller)
                + "); retry shortly";
        ch.writeAndFlush(new CloseWebSocketFrame(1013, why)).addListener(ChannelFutureListener.CLOSE);
    }
}
