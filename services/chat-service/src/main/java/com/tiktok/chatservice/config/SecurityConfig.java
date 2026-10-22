package com.tiktok.chatservice.config;

import com.tiktok.common.http.InternalApiToken;
import com.tiktok.security.internal.InternalCallerAuthorization;
import com.tiktok.security.jwt.JwtAuthenticationFilter;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
                        // WebSocket handshake authenticates itself via JwtHandshakeInterceptor
                        // (browsers can't attach an Authorization header to this request)
                        .requestMatchers("/ws/**").permitAll()
                        // Service-to-service only. api-gateway denies this prefix, and the token keeps
                        // out whatever reaches this port without going through the gateway.
                        // Any method: a HEAD is answered by the GET handler, so a GET-only rule lets a JWT in.
                        .requestMatchers("/api/v1/conversations/internal/**")
                        .access(new InternalCallerAuthorization(internalApiToken))
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
