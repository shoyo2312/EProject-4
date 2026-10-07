package com.tiktok.apigateway.security;

import com.tiktok.crypto.jwt.JwtProvider;
import com.tiktok.crypto.jwt.RevocationKeys;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 * <p>Also rejects tokens auth-service revoked before their natural expiry (see
 * AccessTokenBlacklist there): a single jti on logout, or every token issued to a user before a
 * cutoff instant when all sessions had to die at once — password reset, refresh-token replay.
 * The revocation check fails open — if Redis is unreachable, the
 * token is treated as not blacklisted and accepted based on signature/expiry alone, since
 * Redis here is best-effort revocation, not the source of truth for token validity.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtReactiveAuthenticationManager implements ReactiveAuthenticationManager {

    private static final String CLAIM_ROLE = "role";

    private final JwtProvider jwtProvider;
    private final ReactiveStringRedisTemplate redisTemplate;

    @Override
    public Mono<Authentication> authenticate(Authentication authentication) {
        String token = (String) authentication.getCredentials();

        Claims claims = jwtProvider.parseAccessToken(token);
        if (claims == null) {
            return Mono.error(new BadCredentialsException("Invalid or expired token"));
        }

        return isRevoked(claims).flatMap(revoked -> {
            if (revoked) {
                return Mono.error(new BadCredentialsException("Token has been revoked"));
            }
            return Mono.just(toAuthentication(claims, token));
        });
    }

    /**
     * The logged-out jti and the user-wide cutoff in one MGET. The cutoff exists because a
     * password reset or a detected refresh-token replay ends every session for a user at once,
     * and access tokens are stateless — there is no list of ids to blacklist, so auth-service
     * writes one instant per user and every token issued before it is refused.
     */
    private Mono<Boolean> isRevoked(Claims claims) {
        return redisTemplate.opsForValue().multiGet(RevocationKeys.keysOf(claims))
                .map(values -> RevocationKeys.isRevoked(claims, values))
                .defaultIfEmpty(false)
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
