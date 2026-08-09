package com.tiktok.userservice.config;

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
 * from configuration. The cap in application.yml was therefore never in force on any list
 * endpoint: {@code /followers?size=100000} really did try to return 100000 rows, which is the
 * one thing that setting was added to prevent. Nothing logged, nothing failed.
 *
 * <p>default-page-size hid the problem: Spring Data's own fallback is also 20, so the
 * unconfigured resolver behaved correctly right up until someone passed an explicit size.
 *
 * <p>Boot 3.3 has no property for the serialization mode, so the annotation cannot be dropped
 * in favour of configuration — the two have to coexist, and this is what makes them.
 * UserProfileControllerTest asserts the cap against the resolver's real output.
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
