package com.tiktok.apigateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * @param trustedProxies addresses or CIDR ranges of the proxies whose {@code X-Forwarded-For} the
 *                       rate limiter believes. A request from anywhere else is keyed on the
 *                       connection's own address, whatever the header says.
 */
@ConfigurationProperties(prefix = "gateway.rate-limit")
public record RateLimitProperties(
        List<String> trustedProxies
) {
}
