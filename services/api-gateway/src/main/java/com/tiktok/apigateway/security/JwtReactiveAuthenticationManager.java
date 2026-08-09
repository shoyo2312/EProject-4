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

        if (!jwtProvider.isValidAccessToken(token)) {
            return Mono.error(new BadCredentialsException("Invalid or expired token"));
        }

        Claims claims = jwtProvider.extractClaims(token);

        return isRevoked(claims).flatMap(revoked -> {
            if (revoked) {
                return Mono.error(new BadCredentialsException("Token has been revoked"));
            }
            return Mono.just(toAuthentication(claims, token));
        });
    }

    private Mono<Boolean> isRevoked(Claims claims) {
        String jti = RevocationKeys.jtiOf(claims);
        Mono<Boolean> byJti = jti == null
                ? Mono.just(false)
                : redisTemplate.hasKey(RevocationKeys.forJti(jti));

        return byJti
                .flatMap(revoked -> revoked ? Mono.just(true) : isIssuedBeforeUserCutoff(claims))
                .onErrorResume(ex -> {
                    log.warn("Redis unavailable for blacklist check, failing open", ex);
                    return Mono.just(false);
                });
    }

    /**
     * A password reset or a detected refresh-token replay ends every session for a user at once,
     * but access tokens are stateless — there is no list of ids to blacklist. auth-service writes
     * one cutoff instant per user instead, and every token issued before it is refused.
     */
    private Mono<Boolean> isIssuedBeforeUserCutoff(Claims claims) {
        return redisTemplate.opsForValue().get(RevocationKeys.forUser(claims.getSubject()))
                .map(cutoff -> RevocationKeys.isIssuedBefore(claims, cutoff))
                .defaultIfEmpty(false);
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
