package com.tiktok.videoservice.config;

import com.tiktok.common.http.InternalApiToken;
import com.tiktok.security.internal.InternalCallerAuthorization;
import com.tiktok.security.jwt.JwtAuthenticationFilter;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final InternalApiToken internalApiToken;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        // Before the blanket GET rule below, which would otherwise make the
                        // whole platform's video list — every owner, every status, including
                        // taken-down ones — readable without a token.
                        .requestMatchers("/api/v1/videos/admin", "/api/v1/videos/admin/**")
                        .hasRole("ADMIN")
                        // Also before the blanket GET rule: /policy answers for every video whatever
                        // its visibility, for interaction- and notification-service. api-gateway
                        // denies it; the token keeps out whatever reaches this port without the gateway.
                        .requestMatchers(HttpMethod.GET, "/api/v1/videos/*/policy")
                        .access(new InternalCallerAuthorization(internalApiToken))
                        .requestMatchers(HttpMethod.GET, "/api/v1/videos/**").permitAll()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
