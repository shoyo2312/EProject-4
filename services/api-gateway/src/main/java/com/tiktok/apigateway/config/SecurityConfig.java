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
        // A token that fails — expired, revoked, forged — is a 401 on a public path too, not a
        // quiet downgrade to guest. The clients refresh once on a 401 and retry; a guest answer
        // to a signed-in viewer is wrong data with nothing to say so: every heart unliked, their
        // own private videos gone. No token at all is the guest, and passes as one.
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
                        // Service-to-service endpoints the owning service leaves open because only
                        // other services should reach them. First, so no later rule can open them.
                        .pathMatchers("/api/v1/users/internal/**", "/api/v1/interactions/internal/**",
                                "/api/v1/admin/internal/**", "/api/v1/videos/internal/**",
                                "/api/v1/conversations/internal/**").denyAll()
                        // Unfiltered by visibility on purpose (interaction-service needs the owner
                        // of PRIVATE videos too), so from outside it would name the owner of any
                        // private video id.
                        .pathMatchers("/api/v1/videos/*/policy").denyAll()
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
                        // A profile is public like the videos on it: a signed-out viewer opens
                        // /@handle and sees who wrote a comment. A numeric id only, so /me,
                        // /search and every other one-segment read beside it keep the token.
                        .pathMatchers(HttpMethod.GET,
                                "/api/v1/users",
                                "/api/v1/users/{userId:\\d+}",
                                "/api/v1/users/by-username/*").permitAll()
                        // The reads interaction-service leaves public itself, so a signed-out
                        // viewer sees a video's counts, comments and their replies; like-status
                        // answers a guest liked=false rather than refusing one. Missing here, the
                        // catch-all below answered them 401 whatever the service said.
                        .pathMatchers(HttpMethod.GET,
                                "/api/v1/interactions/videos/*/comments",
                                "/api/v1/interactions/videos/*/comments/*/replies",
                                "/api/v1/interactions/videos/*/counts",
                                "/api/v1/interactions/videos/counts/batch",
                                "/api/v1/interactions/videos/*/like-status").permitAll()
                        // A profile's stories, a single story and the avatar rings, like the videos
                        // beside them: story-service shows a signed-out viewer only PUBLIC stories
                        // of public accounts. The feed is matched first because /stories/* would
                        // cover it. The rings are named although it covers them too, so the
                        // intent shows.
                        .pathMatchers(HttpMethod.GET, "/api/v1/stories/feed").authenticated()
                        .pathMatchers(HttpMethod.GET, "/api/v1/stories/rings", "/api/v1/stories/users/*",
                                "/api/v1/stories/*").permitAll()
                        // The chat handshake carries its token as a query parameter, not a
                        // header: a browser cannot set one on a WebSocket/SockJS handshake.
                        // chat-service validates it itself in JwtHandshakeInterceptor and
                        // refuses the upgrade with a 401 — this filter would reject every
                        // handshake before it ever got there.
                        .pathMatchers("/ws/**").permitAll()
                        // Served on the management port only (application.yml), which nothing
                        // publishes; on 8080 there is no /actuator to reach. A probe and a scraper
                        // carry no token.
                        .pathMatchers("/actuator/**").permitAll()
                        .anyExchange().authenticated())
                .addFilterAt(authenticationWebFilter, SecurityWebFiltersOrder.AUTHENTICATION)
                .build();
    }
}
