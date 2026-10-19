package com.tiktok.apigateway.config;

import com.tiktok.common.http.InternalApiToken;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

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
 *
 * <p>That only covers callers who come through here. auth-service also listens on its own port,
 * and whatever reaches it there writes the header however it likes, so requests routed to
 * auth-service carry the internal API token as well and auth-service believes the header only
 * beside it. Only that route: on every route the token would turn every user request through the
 * gateway into an internal caller, and the internal endpoints would be back to trusting nothing
 * but the gateway's path rules. A token the client sent itself is dropped on every route.
 */
@Component
public class ClientAddressFilter implements GlobalFilter, Ordered {

    static final String CLIENT_IP = "X-Client-Ip";

    /** The route in application.yml whose service reads {@code X-Client-Ip}. */
    static final String AUTH_ROUTE = "auth-service";

    private final TrustedProxies trustedProxies;
    private final InternalApiToken internalApiToken;

    /**
     * Reads the rate limiter's proxy list rather than a second one: which hops may name the
     * client is one fact about the deployment, and two lists is how this and the rate limiter end
     * up disagreeing about who called.
     */
    ClientAddressFilter(RateLimitProperties properties, InternalApiToken internalApiToken) {
        this.trustedProxies = TrustedProxies.parse(properties.trustedProxies());
        this.internalApiToken = internalApiToken;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String client = RateLimiterConfig.clientAddress(exchange.getRequest(), trustedProxies);
        Route route = exchange.getAttribute(GATEWAY_ROUTE_ATTR);
        boolean toAuth = route != null && AUTH_ROUTE.equals(route.getId());

        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.set(CLIENT_IP, client);
                    if (toAuth) {
                        headers.set(InternalApiToken.HEADER, internalApiToken.value());
                    } else {
                        headers.remove(InternalApiToken.HEADER);
                    }
                })
                .build();

        return chain.filter(exchange.mutate().request(request).build());
    }

    /** Before anything that reads the request or forwards it on. */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
