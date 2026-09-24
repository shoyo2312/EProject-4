package com.tiktok.apigateway.config;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Tells downstream services which address a request actually came from, in a header they can
 * believe.
 *
 * <p>The gateway is the only process that can work this out. It terminates the client's
 * connection and it holds the trusted-proxy list, so by the time a request reaches auth-service
 * the original address survives only inside {@code X-Forwarded-For} — a header whose left-hand
 * entries the client writes itself. A security log that trusted those would faithfully record
 * whatever address an attacker typed, which is worse than recording none: it reads as evidence.
 *
 * <p>So the resolution happens once, here, using the same walk the rate limiter uses, and the
 * answer goes downstream as {@code X-Client-Ip}. The header is {@code set}, not added, which is
 * the point — it drops whatever the caller sent under that name, and without that this would be
 * the easiest header in the system to forge.
 */
@Component
public class ClientAddressFilter implements GlobalFilter, Ordered {

    static final String CLIENT_IP = "X-Client-Ip";

    private final TrustedProxies trustedProxies;

    /**
     * Reads the rate limiter's proxy list rather than a second one: which hops may name the
     * client is one fact about the deployment, and two lists is how this and the rate limiter end
     * up disagreeing about who called.
     */
    ClientAddressFilter(RateLimitProperties properties) {
        this.trustedProxies = TrustedProxies.parse(properties.trustedProxies());
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String client = RateLimiterConfig.clientAddress(exchange.getRequest(), trustedProxies);

        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> headers.set(CLIENT_IP, client))
                .build();

        return chain.filter(exchange.mutate().request(request).build());
    }

    /** Before anything that reads the request or forwards it on. */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
