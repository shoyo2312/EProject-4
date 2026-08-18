package com.tiktok.apigateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import reactor.core.publisher.Mono;

@Configuration
public class RateLimiterConfig {

    private static final String FORWARDED_FOR = "X-Forwarded-For";

    /**
     * Rate-limits by client IP so one abusive caller can't exhaust the bucket for everyone
     * behind a shared account (relevant before login even establishes an identity).
     *
     * <p>The web client reaches us through Next.js, which proxies {@code /api/*} here from its own
     * server, so {@code getRemoteAddress()} is that one server for every viewer on the site — the
     * whole front end sharing a single 20 req/s bucket, which one feed page of twenty videos and
     * their author lookups empties by itself. The first hop of {@code X-Forwarded-For} is the
     * caller the proxy actually saw, so that is the key whenever the header is present.
     *
     * <p>Only trustworthy because nothing but our own proxies can reach this port: the header is
     * client-supplied and trivially forged, so it must stop being honoured the moment the gateway
     * is exposed directly. Restrict it to known proxy addresses before that happens.
     */
    @Bean
    public KeyResolver ipKeyResolver() {
        return exchange -> Mono.justOrEmpty(forwardedFor(exchange.getRequest().getHeaders()))
                .switchIfEmpty(Mono.justOrEmpty(exchange.getRequest().getRemoteAddress())
                        .map(address -> address.getAddress().getHostAddress()))
                .defaultIfEmpty("unknown");
    }

    /** The client the outermost proxy saw: the first entry, not the last one appended. */
    private static String forwardedFor(HttpHeaders headers) {
        String header = headers.getFirst(FORWARDED_FOR);
        if (header == null || header.isBlank()) {
            return null;
        }
        String first = header.split(",", 2)[0].trim();
        return first.isEmpty() ? null : first;
    }
}
