package com.tiktok.videoservice.config;

import org.springframework.boot.autoconfigure.data.web.SpringDataWebProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.web.config.PageableHandlerMethodArgumentResolverCustomizer;

/**
 * Re-applies {@code spring.data.web.pageable.*} by hand, because declaring
 * {@code @EnableSpringDataWebSupport} silently took it away.
 *
 * <p>The annotation registers its own PageableHandlerMethodArgumentResolver. Boot's
 * SpringDataWebAutoConfiguration is {@code @ConditionalOnMissingBean} on exactly that type, so
 * it backs off — and it is the auto-configuration, not Spring Data, that reads max-page-size
 * and default-page-size from configuration. Turning on the DTO serialization therefore
 * uncapped every paged endpoint: {@code ?size=100000} served 100000 rows again, which is the
 * one thing the setting exists to prevent. Nothing fails; the cap just stops existing.
 *
 * <p>Boot 3.3 has no property for the serialization mode, so the annotation cannot be dropped
 * in favour of configuration — the two have to coexist, and this is what makes them.
 * VideoControllerPageSizeTest asserts the cap end-to-end rather than trusting it.
 */
@Configuration
@EnableConfigurationProperties(SpringDataWebProperties.class)
public class PageableConfig {

    @Bean
    public PageableHandlerMethodArgumentResolverCustomizer pageableCustomizer(SpringDataWebProperties properties) {
        SpringDataWebProperties.Pageable pageable = properties.getPageable();

        return resolver -> {
            resolver.setMaxPageSize(pageable.getMaxPageSize());
            resolver.setFallbackPageable(PageRequest.of(0, pageable.getDefaultPageSize()));
            resolver.setOneIndexedParameters(pageable.isOneIndexedParameters());
            resolver.setPageParameterName(pageable.getPageParameter());
            resolver.setSizeParameterName(pageable.getSizeParameter());
            resolver.setPrefix(pageable.getPrefix());
            resolver.setQualifierDelimiter(pageable.getQualifierDelimiter());
        };
    }
}
