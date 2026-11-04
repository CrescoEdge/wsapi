package io.cresco.wsapi.auth;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.regex.Pattern;

/**
 * What an authentication attempt is counted against: {@code "<bucket> <address>"}. The bucket is an IPv4 address, or the
 * IPv6 prefix of {@link #ipv6PrefixBits} bits (a /64 by default, one host's usual allocation; 128 where a /64 is shared,
 * e.g. by a hosting provider). An IPv4-mapped address, and an IPv4 address embedded by NAT64 (the well-known
 * 64:ff9b::/96 and the local-use 64:ff9b:1::/96), are that IPv4 address: every IPv4 client behind a NAT64 would
 * otherwise share one /64. The address is the exact client address; for IPv4 the two are one string.
 */
public final class Sources {

    private Sources() {}

    private static volatile int ipv6PrefixBits = 64;

    /** The IPv6 prefix length a bucket aggregates (wsapi_auth_ipv6_prefix_bits, 32 to 128; 64 by default). */
    public static void ipv6PrefixBits(int bits) {
        ipv6PrefixBits = Math.max(32, Math.min(128, bits));
    }

    public static int ipv6PrefixBits() { return ipv6PrefixBits; }

    /** A literal IP address (never a host name: nothing here resolves names). */
    private static final Pattern IPV6_LITERAL = Pattern.compile("[0-9A-Fa-f:.]*:[0-9A-Fa-f:.]*:[0-9A-Fa-f:.]*");

    /** The source of a client address. */
    public static String of(InetAddress addr) {
        if (!(addr instanceof Inet6Address)) return addr.getHostAddress();
        byte[] b = addr.getAddress();
        String v4 = embeddedIpv4(b);
        if (v4 != null) return v4;
        String exact = groups(b);
        int bits = ipv6PrefixBits;
        if (bits >= 128) return exact;
        byte[] p = b.clone();
        for (int k = 0; k < 16; k++) {
            int keep = Math.max(0, Math.min(8, bits - 8 * k));
            p[k] = (byte) (p[k] & (0xff << (8 - keep)));
        }
        String bucket;
        if (bits % 16 == 0) {
            StringBuilder sb = new StringBuilder();
            for (int k = 0; k < bits / 8; k += 2) {
                if (k > 0) sb.append(':');
                sb.append(Integer.toHexString(((p[k] & 0xff) << 8) | (p[k + 1] & 0xff)));
            }
            bucket = sb.append("::/").append(bits).toString();
        } else {
            bucket = groups(p) + "/" + bits;
        }
        return bucket + " " + exact;
    }

    /**
     * A source as given, or, for a bare IPv6 literal (a caller that passed the client address itself), the source of that
     * address: the limiter aggregates addresses whoever calls it, so a /64 is one source however it is presented.
     */
    public static String normalize(String source) {
        if (source == null || source.indexOf(' ') >= 0 || source.indexOf('/') >= 0) return source;
        if (!IPV6_LITERAL.matcher(source).matches()) return source;
        try {
            return of(InetAddress.getByName(source));                 // a literal: no name lookup
        } catch (Exception notAnAddress) {
            return source;
        }
    }

    /** The bucket of a source. */
    public static String bucket(String source) {
        if (source == null) return "";
        int sp = source.indexOf(' ');
        return sp < 0 ? source : source.substring(0, sp);
    }

    /** The exact address of a source. */
    public static String address(String source) {
        if (source == null) return "";
        int sp = source.indexOf(' ');
        return sp < 0 ? source : source.substring(sp + 1);
    }

    /** The IPv4 address an IPv6 address carries: IPv4-mapped ::ffff:a.b.c.d, or NAT64 64:ff9b::/96 and 64:ff9b:1::/96. */
    static String embeddedIpv4(byte[] b) {
        boolean mapped = true;
        for (int k = 0; k < 10; k++) if (b[k] != 0) mapped = false;
        mapped = mapped && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff;
        boolean wellKnownPrefix = (b[0] & 0xff) == 0x00 && (b[1] & 0xff) == 0x64 && (b[2] & 0xff) == 0xff && (b[3] & 0xff) == 0x9b;
        boolean nat64 = wellKnownPrefix;
        for (int k = 4; k < 12 && nat64; k++) if (b[k] != 0) nat64 = false;
        boolean localUse = wellKnownPrefix && b[4] == 0 && (b[5] & 0xff) == 0x01;
        for (int k = 6; k < 12 && localUse; k++) if (b[k] != 0) localUse = false;
        if (!mapped && !nat64 && !localUse) return null;
        return (b[12] & 0xff) + "." + (b[13] & 0xff) + "." + (b[14] & 0xff) + "." + (b[15] & 0xff);
    }

    /** The eight groups of an IPv6 address, in hex, uncompressed (no zone). */
    static String groups(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < 16; k += 2) {
            if (k > 0) sb.append(':');
            sb.append(Integer.toHexString(((b[k] & 0xff) << 8) | (b[k + 1] & 0xff)));
        }
        return sb.toString();
    }
}
