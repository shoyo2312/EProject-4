package com.tiktok.userservice.config;

import com.tiktok.common.http.InternalApiToken;
import com.tiktok.security.internal.InternalCallerAuthorization;
import com.tiktok.security.jwt.JwtAuthenticationFilter;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
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
                        // Counts only, no PII — same trust level as interaction-service's
                        // counts/batch endpoint that chat-service's realtime fan-out already calls.
                        .requestMatchers(HttpMethod.GET, "/api/v1/users/stats/batch").permitAll()
                        // A profile is public like the videos on it — the response carries no PII.
                        // A guest is a null viewer, which no block can hide anything from. A
                        // numeric id only, so /me and /search keep the token.
                        .requestMatchers(HttpMethod.GET, "/api/v1/users", "/api/v1/users/{userId:\\d+}",
                                "/api/v1/users/by-username/*").permitAll()
                        // Service-to-service only. api-gateway denies this prefix, and the token keeps
                        // out whatever reaches this port without going through the gateway.
                        .requestMatchers(HttpMethod.GET, "/api/v1/users/internal/**")
                        .access(new InternalCallerAuthorization(internalApiToken))
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
