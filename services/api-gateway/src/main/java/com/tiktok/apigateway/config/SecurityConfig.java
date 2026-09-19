package com.tiktok.apigateway.config;

import com.tiktok.apigateway.security.JwtReactiveAuthenticationManager;
import com.tiktok.apigateway.security.JwtServerAuthenticationConverter;
import com.tiktok.apigateway.security.RestAccessDeniedHandler;
import com.tiktok.apigateway.security.RestAuthenticationEntryPoint;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.AuthenticationWebFilter;

@Configuration
@EnableWebFluxSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtServerAuthenticationConverter jwtServerAuthenticationConverter;
    private final JwtReactiveAuthenticationManager jwtReactiveAuthenticationManager;
    private final RestAuthenticationEntryPoint restAuthenticationEntryPoint;
    private final RestAccessDeniedHandler restAccessDeniedHandler;

    @Bean
    public SecurityWebFilterChain filterChain(ServerHttpSecurity http) {
        AuthenticationWebFilter authenticationWebFilter = new AuthenticationWebFilter(jwtReactiveAuthenticationManager);
        authenticationWebFilter.setServerAuthenticationConverter(jwtServerAuthenticationConverter);
        authenticationWebFilter.setAuthenticationFailureHandler((webFilterExchange, exception) ->
                restAuthenticationEntryPoint.commence(webFilterExchange.getExchange(), exception));

        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(restAuthenticationEntryPoint)
                        .accessDeniedHandler(restAccessDeniedHandler))
                .authorizeExchange(exchanges -> exchanges
                        // Ahead of the permitAll below, which would otherwise swallow it: the
                        // admin console's user directory shares auth-service's public prefix but
                        // is not a sign-in endpoint. auth-service still enforces ROLE_ADMIN — this
                        // only stops the whole platform's account list from sitting behind the one
                        // prefix that lets anonymous traffic through.
                        .pathMatchers("/api/v1/auth/admin/**").authenticated()
                        .pathMatchers("/api/v1/auth/**", "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        // Same shape as the auth rule above: video-service pins this path to
                        // ROLE_ADMIN itself, and the gateway saying "public" for an admin path
                        // means the platform's whole video list — every owner, every status,
                        // including taken-down ones — rides on that one rule staying correct.
                        .pathMatchers("/api/v1/videos/admin", "/api/v1/videos/admin/**").authenticated()
                        .pathMatchers(HttpMethod.GET, "/api/v1/videos/**").permitAll()
                        .pathMatchers(HttpMethod.GET, "/api/v1/recommendations/trending").permitAll()
                        .pathMatchers(HttpMethod.GET, "/api/v1/search/**").permitAll()
                        // A repost is public, like the videos on a profile — the Reposts tab
                        // has to load for a signed-out viewer too.
                        .pathMatchers(HttpMethod.GET, "/api/v1/interactions/users/*/reposts").permitAll()
                        // The chat handshake carries its token as a query parameter, not a
                        // header: a browser cannot set one on a WebSocket/SockJS handshake.
                        // chat-service validates it itself in JwtHandshakeInterceptor and
                        // refuses the upgrade with a 401 — this filter would reject every
                        // handshake before it ever got there.
                        .pathMatchers("/ws/**").permitAll()
                        .anyExchange().authenticated())
                .addFilterAt(authenticationWebFilter, SecurityWebFiltersOrder.AUTHENTICATION)
                .build();
    }
}
