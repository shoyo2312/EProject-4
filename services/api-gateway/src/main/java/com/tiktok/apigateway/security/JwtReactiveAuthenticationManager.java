package com.tiktok.apigateway.security;

import com.tiktok.crypto.jwt.JwtProvider;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
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
 *
 * <p>Only access tokens are accepted: a refresh token carries the same signature and would
 * otherwise work as a bearer credential for its full 7-day life. See
 * {@link com.tiktok.crypto.jwt.JwtProvider#isValidAccessToken(String)}.
 *
 * <p>Also rejects tokens whose jti was blacklisted by auth-service on logout (see
 * AccessTokenBlacklist there). The blacklist check fails open — if Redis is unreachable, the
 * token is treated as not blacklisted and accepted based on signature/expiry alone, since
 * Redis here is best-effort revocation, not the source of truth for token validity.
 */
@Component
@RequiredArgsConstructor
public class JwtReactiveAuthenticationManager implements ReactiveAuthenticationManager {

    private static final Logger log = LoggerFactory.getLogger(JwtReactiveAuthenticationManager.class);
    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_JTI = "jti";
    private static final String BLACKLIST_KEY_PREFIX = "auth:blacklist:";

    private final JwtProvider jwtProvider;
    private final ReactiveStringRedisTemplate redisTemplate;

    @Override
    public Mono<Authentication> authenticate(Authentication authentication) {
        String token = (String) authentication.getCredentials();

        if (!jwtProvider.isValidAccessToken(token)) {
            return Mono.error(new BadCredentialsException("Invalid or expired token"));
        }

        Claims claims = jwtProvider.extractClaims(token);
        String jti = claims.get(CLAIM_JTI, String.class);

        return isBlacklisted(jti).flatMap(blacklisted -> {
            if (blacklisted) {
                return Mono.error(new BadCredentialsException("Token has been revoked"));
            }
            return Mono.just(toAuthentication(claims, token));
        });
    }

    private Mono<Boolean> isBlacklisted(String jti) {
        if (jti == null) {
            return Mono.just(false);
        }
        return redisTemplate.hasKey(BLACKLIST_KEY_PREFIX + jti)
                .onErrorResume(ex -> {
                    log.warn("Redis unavailable for blacklist check, failing open", ex);
                    return Mono.just(false);
                });
    }

    private Authentication toAuthentication(Claims claims, String token) {
        Long userId = Long.valueOf(claims.getSubject());
        String role = claims.get(CLAIM_ROLE, String.class);

        var authorities = role != null
                ? List.of(new SimpleGrantedAuthority("ROLE_" + role))
                : List.<SimpleGrantedAuthority>of();

        return new UsernamePasswordAuthenticationToken(userId, token, authorities);
    }
}
