package com.tiktok.apigateway.config;

import io.netty.util.NetUtil;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.core.Authentication;
import reactor.core.publisher.Mono;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.Principal;
import java.util.Arrays;
import java.util.List;

@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimiterConfig {

    private static final String FORWARDED_FOR = "X-Forwarded-For";

    /**
     * Rate-limits a signed-in admin by account, and every other request by client IP so one
     * abusive caller can't exhaust the bucket for everyone behind a shared account (relevant
     * before login even establishes an identity).
     *
     * <p>Admins by account because the admin console calls this gateway from its own server: every
     * admin it serves arrives from that one address, and a few of them on the videos page empty a
     * shared 20 req/s bucket between them. The account is a key nobody can forge — the
     * authentication filter has verified the token before any route filter runs, and a token that
     * fails is a 401 there, not a guest. Only admins: anyone can create an ordinary account, and
     * keying those by account would hand out a fresh bucket per sign-up.
     *
     * <p>The web client reaches us through Next.js, which proxies {@code /api/*} here from its own
     * server, so {@code getRemoteAddress()} is that one server for every viewer on the site — the
     * whole front end sharing a single 20 req/s bucket, which one feed page of twenty videos and
     * their author lookups empties by itself. The client has to come from {@code X-Forwarded-For}.
     *
     * <p>The header is only believed from a trusted proxy, and only read from the right. Proxies
     * append the address they saw, so the entries on the left are whatever the client wrote. This
     * used to take the first entry, which let any caller pick a fresh rate-limit bucket per
     * request by rotating a made-up address. Now the walk starts at the connection's peer and
     * moves left one hop at a time while the current hop is a trusted proxy. The first address
     * that is not a proxy is the client, and nothing to its left is read.
     *
     * <p>This cannot recover what no proxy recorded. Next.js rewrites only set the header when the
     * request has none and never append, so a Next.js server that the internet reaches directly
     * passes a client-written header through unchanged. Put a proxy that sets the header in front
     * of it (Vercel's edge does), or the web path stays spoofable whatever this resolver does.
     */
    @Bean
    public KeyResolver clientKeyResolver(RateLimitProperties properties) {
        TrustedProxies trusted = TrustedProxies.parse(properties.trustedProxies());
        return exchange -> exchange.getPrincipal()
                .filter(RateLimiterConfig::isAdmin)
                .map(admin -> "admin:" + admin.getName())
                .switchIfEmpty(Mono.fromSupplier(() -> clientAddress(exchange.getRequest(), trusted)));
    }

    private static boolean isAdmin(Principal principal) {
        return principal instanceof Authentication authentication
                && authentication.isAuthenticated()
                && authentication.getAuthorities().stream()
                        .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
    }

    static String clientAddress(ServerHttpRequest request, TrustedProxies trusted) {
        InetSocketAddress remote = request.getRemoteAddress();
        if (remote == null || remote.getAddress() == null) {
            return "unknown";
        }

        InetAddress client = remote.getAddress();
        List<String> hops = forwardedFor(request);
        for (int i = hops.size() - 1; i >= 0 && trusted.contains(client); i--) {
            InetAddress hop = TrustedProxies.literal(hops.get(i));
            if (hop == null) {
                // Not an address, so not something a proxy wrote. Keyed on the proxy that passed
                // it on rather than on whatever the entry says.
                break;
            }
            client = hop;
        }
        // toAddressString, not getHostAddress: the latter prints IPv6 uncompressed, so loopback
        // reaches the security log as "0:0:0:0:0:0:0:1" — an address every operator and every
        // tool writes "::1". Netty is already here for TrustedProxies' literal parsing, and this
        // is the RFC 5952 form the rest of the world uses.
        return NetUtil.toAddressString(client);
    }

    /** Every entry of every X-Forwarded-For header, in the order the proxies added them. */
    private static List<String> forwardedFor(ServerHttpRequest request) {
        List<String> headers = request.getHeaders().get(FORWARDED_FOR);
        if (headers == null) {
            return List.of();
        }
        return headers.stream()
                .flatMap(header -> Arrays.stream(header.split(",")))
                .map(String::strip)
                .filter(entry -> !entry.isEmpty())
                .toList();
    }
}
