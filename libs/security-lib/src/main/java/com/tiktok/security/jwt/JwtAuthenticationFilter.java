package com.tiktok.security.jwt;

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
 * Validates access tokens issued by auth-service (same JWT_SECRET) and resolves the
 * current user id from the token subject, so downstream controllers can read it via
 * @AuthenticationPrincipal without ever contacting auth-service directly.
 *
 * <p>Refresh tokens are rejected here: they are signed with the same secret, so accepting any
 * valid JWT would let a 7-day refresh token stand in as a bearer credential. See
 * {@link JwtProvider#isValidAccessToken(String)}.
 *
 * <p>auth-service keeps a near-identical copy of this filter (it owns its own
 * JwtProperties/JwtConfig and does not depend on security-lib) — changes to the checks below
 * must be mirrored there.
 */
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String CLAIM_ROLE = "role";

    private final JwtProvider jwtProvider;
    private final RevokedTokenChecker revokedTokenChecker;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);

        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length());

            if (jwtProvider.isValidAccessToken(token)) {
                Claims claims = jwtProvider.extractClaims(token);

                if (revokedTokenChecker.isRevoked(claims)) {
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
