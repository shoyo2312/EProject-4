package com.tiktok.apigateway.service;

import com.tiktok.apigateway.dto.AuthMeResponse;
import com.tiktok.apigateway.dto.MeResponse;
import com.tiktok.apigateway.dto.ProfileMeResponse;
import com.tiktok.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.Optional;

/**
 * Aggregates the account identity (auth-service) and social profile (user-service) into a
 * single "my account" view for clients, so the frontend doesn't have to call both /me
 * endpoints and stitch the result itself. Each downstream call still carries the original
 * Authorization header, so auth-service and user-service keep validating the JWT themselves
 * (defense in depth) rather than the gateway asserting identity on their behalf.
 *
 * <p>The user-service call is best-effort: a 404 right after register/login is usually just
 * UserRegisteredEventConsumer not having processed the Kafka event yet, so it's worth a short
 * retry; any other failure (timeout, 5xx, connection refused) is a real problem that retrying
 * won't fix, so it's logged and degraded instead of retried. The auth-service call is not
 * degraded — account identity is mandatory — but its status is carried through rather than
 * collapsing into a 500; see {@link #asDownstreamStatus}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MeServiceImpl implements MeService {


    private static final Duration DOWNSTREAM_TIMEOUT = Duration.ofSeconds(3);

    private static final Retry PROFILE_NOT_FOUND_RETRY = Retry.backoff(2, Duration.ofMillis(150))
            .filter(WebClientResponseException.NotFound.class::isInstance)
            // rethrow the last actual failure on exhaustion instead of reactor's default
            // RetryExhaustedException wrapper, so downstream error handling only ever has to
            // check for WebClientResponseException.NotFound, whether retried or not.
            .onRetryExhaustedThrow((retrySpec, signal) -> signal.failure());

    private final WebClient authServiceWebClient;
    private final WebClient userServiceWebClient;

    @Override
    public Mono<MeResponse> getMe(String authorizationHeader) {
        Mono<AuthMeResponse> accountMono = authServiceWebClient.get()
                .uri("/api/v1/auth/me")
                .header(HttpHeaders.AUTHORIZATION, authorizationHeader)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<ApiResponse<AuthMeResponse>>() {
                })
                .map(ApiResponse::data)
                .timeout(DOWNSTREAM_TIMEOUT)
                .onErrorMap(WebClientResponseException.class, MeServiceImpl::asDownstreamStatus);

        Mono<Optional<ProfileMeResponse>> profileMono = userServiceWebClient.get()
                .uri("/api/v1/users/me")
                .header(HttpHeaders.AUTHORIZATION, authorizationHeader)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<ApiResponse<ProfileMeResponse>>() {
                })
                .map(ApiResponse::data)
                .map(Optional::of)
                .timeout(DOWNSTREAM_TIMEOUT)
                .retryWhen(PROFILE_NOT_FOUND_RETRY)
                .onErrorResume(ex -> true, this::degradeProfile);

        return Mono.zip(accountMono, profileMono, MeResponse::from);
    }

    /**
     * Hands the client the status auth-service actually answered with, instead of the 500 that a
     * bare WebClientResponseException reaching the error handler produced. Aggregating two calls is
     * an implementation detail of this endpoint; a caller whose token expired mid-request deserves
     * the 401 that tells it to refresh, not a server error that says the fault was ours.
     *
     * <p>The reason travels, the body does not: auth-service's own error envelope is not this
     * response's to forward, and re-wrapping it would put two different error shapes behind one
     * endpoint.
     */
    private static Throwable asDownstreamStatus(WebClientResponseException ex) {
        log.warn("auth-service answered {} while aggregating GET /api/v1/me", ex.getStatusCode());
        return new ResponseStatusException(ex.getStatusCode(), "auth-service rejected the request", ex);
    }

    private Mono<Optional<ProfileMeResponse>> degradeProfile(Throwable ex) {
        if (!(ex instanceof WebClientResponseException.NotFound)) {
            log.error("user-service call failed while aggregating GET /api/v1/me, "
                    + "degrading profile fields to null", ex);
        }
        return Mono.just(Optional.empty());
    }
}
