package com.tiktok.common.config;

import com.tiktok.common.http.InternalApiToken;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.Environment;

@AutoConfiguration
public class InternalApiAutoConfiguration {

    /**
     * Lazy, so only a service that injects it — one that calls or serves an internal endpoint —
     * needs INTERNAL_API_TOKEN, and there it is still checked at startup, when that injection is.
     */
    @Bean
    @Lazy
    @ConditionalOnMissingBean
    public InternalApiToken internalApiToken(Environment environment) {
        return InternalApiToken.from(environment);
    }
}
