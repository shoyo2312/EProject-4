package com.tiktok.authservice.security;

import com.tiktok.authservice.service.AccessTokenBlacklist;
import com.tiktok.crypto.jwt.JwtProvider;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Local equivalent of security-lib's filter, kept separate per CLAUDE.md — auth-service owns
 * its own JwtProvider/JwtConfig instead of depending on security-lib. Only needed for /me;
 * register/login/refresh/logout stay permitAll and never reach this filter's auth check.
 *
 * <p>Keep the checks below in sync with {@code com.tiktok.security.jwt.JwtAuthenticationFilter}:
 * a bearer token must be an access token (refresh tokens are signed with the same secret and
 * would otherwise authenticate for 7 days) and must not have been revoked by logout.
 */
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String CLAIM_ROLE = "role";

    private final JwtProvider jwtProvider;
    private final AccessTokenBlacklist accessTokenBlacklist;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);

        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length());

            if (jwtProvider.isValidAccessToken(token)) {
                Claims claims = jwtProvider.extractClaims(token);

                if (accessTokenBlacklist.isBlacklisted(claims)) {
                    filterChain.doFilter(request, response);
                    return;
                }

                Long userId = Long.valueOf(claims.getSubject());
                String role = claims.get(CLAIM_ROLE, String.class);

                var authorities = role != null
                        ? List.of(new SimpleGrantedAuthority("ROLE_" + role))
                        : List.<SimpleGrantedAuthority>of();

                var authentication = new UsernamePasswordAuthenticationToken(userId, null, authorities);
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        }

        filterChain.doFilter(request, response);
    }
}
