package com.tiktok.apigateway.config;

import io.netty.util.NetUtil;

import java.net.InetAddress;
import java.util.List;

/**
 * The address ranges allowed to tell the gateway who the client is through
 * {@code X-Forwarded-For}.
 *
 * <p>Addresses are parsed with Netty's literal-only parser, never {@code InetAddress.getByName}:
 * the header is client-controlled, and a hostname in it would otherwise be a DNS lookup that the
 * client chooses, made on the event loop.
 */
final class TrustedProxies {

    private final List<Range> ranges;

    private TrustedProxies(List<Range> ranges) {
        this.ranges = ranges;
    }

    /**
     * @param cidrs addresses ({@code 10.0.0.7}) or CIDR ranges ({@code 10.0.0.0/8}, {@code fc00::/7})
     * @throws IllegalArgumentException for an entry that is neither, so a typo fails startup
     *                                  instead of silently trusting nothing
     */
    static TrustedProxies parse(List<String> cidrs) {
        return new TrustedProxies(cidrs == null ? List.of() : cidrs.stream().map(Range::parse).toList());
    }

    /** The address a header entry names, or null when it is not an IP literal. */
    static InetAddress literal(String value) {
        return value == null ? null : NetUtil.createInetAddressFromIpAddressString(value.strip());
    }

    boolean contains(InetAddress address) {
        byte[] bytes = address.getAddress();
        return ranges.stream().anyMatch(range -> range.contains(bytes));
    }

    private record Range(byte[] network, int prefixBits) {

        static Range parse(String cidr) {
            String[] parts = cidr.strip().split("/", 2);
            InetAddress address = literal(parts[0]);
            if (address == null) {
                throw new IllegalArgumentException("Not an IP address or CIDR range: " + cidr);
            }
            byte[] network = address.getAddress();
            int maxBits = network.length * 8;
            int prefixBits;
            try {
                prefixBits = parts.length == 2 ? Integer.parseInt(parts[1].strip()) : maxBits;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Not an IP address or CIDR range: " + cidr, e);
            }
            if (prefixBits < 0 || prefixBits > maxBits) {
                throw new IllegalArgumentException("Prefix out of range in " + cidr);
            }
            return new Range(network, prefixBits);
        }

        /** An IPv4 range never contains an IPv6 address, and the other way round. */
        boolean contains(byte[] address) {
            if (address.length != network.length) {
                return false;
            }
            int fullBytes = prefixBits / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (address[i] != network[i]) {
                    return false;
                }
            }
            int remainingBits = prefixBits % 8;
            if (remainingBits == 0) {
                return true;
            }
            int mask = 0xFF << (8 - remainingBits);
            return (address[fullBytes] & mask) == (network[fullBytes] & mask);
        }
    }
}
