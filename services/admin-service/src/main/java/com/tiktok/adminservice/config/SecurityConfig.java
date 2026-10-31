package com.tiktok.adminservice.config;

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

/**
 * Only report submission is open to any authenticated user — everything else here is a
 * moderation action and requires ROLE_ADMIN (issued by auth-service in the JWT). The one exception
 * is the internal prefix, which the services that screen text poll with the internal token.
 */
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
                        // Service-to-service: api-gateway denies this prefix, and the token keeps out
                        // whatever reaches this port without going through the gateway. Before the
                        // ROLE_ADMIN rule, which no service's call could ever satisfy.
                        // Any method: a HEAD is answered by the GET handler, so a GET-only rule lets a JWT in.
                        .requestMatchers("/api/v1/admin/internal/**")
                        .access(new InternalCallerAuthorization(internalApiToken))
                        .requestMatchers(HttpMethod.POST, "/api/v1/admin/reports", "/api/v1/admin/reports/direct-message")
                        .authenticated()
                        .anyRequest().hasRole("ADMIN")
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
