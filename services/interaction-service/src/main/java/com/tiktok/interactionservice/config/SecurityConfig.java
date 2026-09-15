package com.tiktok.interactionservice.config;

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

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        // Before the permissive comment rules below. The admin listing returns
                        // removed comments and every comment on a video regardless of who posted
                        // it — a moderation view, not the public thread.
                        .requestMatchers("/api/v1/interactions/admin/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/v1/interactions/videos/*/like-status").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/interactions/videos/*/comments").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/interactions/videos/counts/batch").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/interactions/videos/*/counts").permitAll()
                        // A repost is public, exactly like the videos a profile posted.
                        .requestMatchers(HttpMethod.GET, "/api/v1/interactions/users/*/reposts").permitAll()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
