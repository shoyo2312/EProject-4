package com.tiktok.apigateway.security;

import com.tiktok.crypto.jwt.JwtProvider;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Rejects requests with an invalid/expired token at the edge, before they ever reach a
 * downstream service. Each service still validates the token itself (defense in depth,
 * same JWT_SECRET) — this is just the first line of defense.
 */
@Component
@RequiredArgsConstructor
public class JwtReactiveAuthenticationManager implements ReactiveAuthenticationManager {

    private static final String CLAIM_ROLE = "role";

    private final JwtProvider jwtProvider;

    @Override
    public Mono<Authentication> authenticate(Authentication authentication) {
        String token = (String) authentication.getCredentials();

        if (!jwtProvider.isValid(token)) {
            return Mono.error(new BadCredentialsException("Invalid or expired token"));
        }

        Claims claims = jwtProvider.extractClaims(token);
        Long userId = Long.valueOf(claims.getSubject());
        String role = claims.get(CLAIM_ROLE, String.class);

        var authorities = role != null
                ? List.of(new SimpleGrantedAuthority("ROLE_" + role))
                : List.<SimpleGrantedAuthority>of();

        return Mono.just(new UsernamePasswordAuthenticationToken(userId, token, authorities));
    }
}
