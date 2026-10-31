package com.tiktok.storyservice.config;

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
 * A profile's stories, a single story and the avatar rings are readable signed out — the service
 * decides per story what an anonymous viewer may see, which is PUBLIC stories of public accounts.
 * The feed needs a viewer, and {@code /stories/*} would otherwise match {@code /stories/feed}, so it
 * is matched first. {@code /stories/*} covers the rings too; they are named so the intent shows.
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
                        // Service-to-service only, ahead of every stories rule: api-gateway denies this
                        // prefix, and the token keeps out whatever reaches this port without going
                        // through the gateway. Left to anyRequest(), a signed-in user would get in.
                        // Any method, not just GET: a HEAD is answered by the GET handler, so a rule
                        // for GET alone lets a user's JWT ask it.
                        .requestMatchers("/api/v1/stories/internal/**")
                        .access(new InternalCallerAuthorization(internalApiToken))
                        .requestMatchers(HttpMethod.GET, "/api/v1/stories/feed").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/stories/rings", "/api/v1/stories/users/*",
                                "/api/v1/stories/*").permitAll()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
