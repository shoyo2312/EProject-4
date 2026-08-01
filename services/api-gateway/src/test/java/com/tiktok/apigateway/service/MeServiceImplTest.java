package com.tiktok.apigateway.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.json.Jackson2JsonDecoder;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the aggregation logic directly against stubbed WebClients (no real network call,
 * no Spring context) — each stub replays a scripted sequence of responses (or a fixed
 * exception) regardless of the outgoing request, which is enough to exercise
 * merge / retry / degrade / logging behavior.
 */
class MeServiceImplTest {

    private static final String AUTH_HEADER = "Bearer test-token";

    private static final String ACCOUNT_JSON = """
            {
              "success": true,
              "data": {
                "id": 1,
                "username": "alice",
                "email": "alice@example.com",
                "role": "USER",
                "status": "ACTIVE",
                "createdAt": "2024-01-01T00:00:00Z"
              }
            }
            """;

    private static final String PROFILE_JSON = """
            {
              "success": true,
              "data": {
                "userId": 1,
                "displayName": "Alice A",
                "bio": "hello",
                "avatarUrl": "https://example.com/avatar.png",
                "followerCount": 10,
                "followingCount": 5
              }
            }
            """;

    private static final String PROFILE_NOT_FOUND_JSON = """
            {
              "success": false,
              "code": "USER_PROFILE_NOT_FOUND",
              "message": "No profile found for user id: 1"
            }
            """;

    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void attachLogAppender() {
        logAppender = new ListAppender<>();
        logAppender.start();
        ((Logger) LoggerFactory.getLogger(MeServiceImpl.class)).addAppender(logAppender);
    }

    @AfterEach
    void detachLogAppender() {
        ((Logger) LoggerFactory.getLogger(MeServiceImpl.class)).detachAppender(logAppender);
    }

    @Test
    void getMe_whenBothDownstreamsSucceed_returnsMergedResponseWithProfileReady() {
        SequencedStub userStub = SequencedStub.responses(status(HttpStatus.OK, PROFILE_JSON));

        MeServiceImpl service = new MeServiceImpl(
                accountStub(HttpStatus.OK, ACCOUNT_JSON),
                userStub.client());

        StepVerifier.create(service.getMe(AUTH_HEADER))
                .assertNext(me -> {
                    assertThat(me.id()).isEqualTo(1L);
                    assertThat(me.username()).isEqualTo("alice");
                    assertThat(me.email()).isEqualTo("alice@example.com");
                    assertThat(me.displayName()).isEqualTo("Alice A");
                    assertThat(me.bio()).isEqualTo("hello");
                    assertThat(me.followerCount()).isEqualTo(10L);
                    assertThat(me.followingCount()).isEqualTo(5L);
                    assertThat(me.profileReady()).isTrue();
                })
                .verifyComplete();

        assertThat(userStub.callCount()).isEqualTo(1);
        assertThat(logAppender.list).isEmpty();
    }

    @Test
    void getMe_whenProfileNotFoundOnceThenSucceeds_retriesAndReturnsProfileReady() {
        SequencedStub userStub = SequencedStub.responses(
                status(HttpStatus.NOT_FOUND, PROFILE_NOT_FOUND_JSON),
                status(HttpStatus.OK, PROFILE_JSON));

        MeServiceImpl service = new MeServiceImpl(
                accountStub(HttpStatus.OK, ACCOUNT_JSON),
                userStub.client());

        StepVerifier.create(service.getMe(AUTH_HEADER))
                .assertNext(me -> {
                    assertThat(me.profileReady()).isTrue();
                    assertThat(me.displayName()).isEqualTo("Alice A");
                })
                .verifyComplete();

        assertThat(userStub.callCount()).isEqualTo(2);
        assertThat(logAppender.list).isEmpty();
    }

    @Test
    void getMe_whenProfileNotFoundOnEveryRetry_degradesWithoutLoggingError() {
        SequencedStub userStub = SequencedStub.responses(status(HttpStatus.NOT_FOUND, PROFILE_NOT_FOUND_JSON));

        MeServiceImpl service = new MeServiceImpl(
                accountStub(HttpStatus.OK, ACCOUNT_JSON),
                userStub.client());

        StepVerifier.create(service.getMe(AUTH_HEADER))
                .assertNext(me -> {
                    assertThat(me.profileReady()).isFalse();
                    assertThat(me.displayName()).isNull();
                    assertThat(me.bio()).isNull();
                    assertThat(me.avatarUrl()).isNull();
                    assertThat(me.followerCount()).isNull();
                    assertThat(me.followingCount()).isNull();
                })
                .verifyComplete();

        // Retry.backoff(2, ...) => 1 initial attempt + 2 retries = 3 calls total.
        assertThat(userStub.callCount()).isEqualTo(3);
        assertThat(logAppender.list).isEmpty();
    }

    @Test
    void getMe_whenUserServiceTimesOut_degradesWithoutRetryAndLogsError() {
        SequencedStub userStub = SequencedStub.failingWith(new TimeoutException("simulated timeout"));

        MeServiceImpl service = new MeServiceImpl(
                accountStub(HttpStatus.OK, ACCOUNT_JSON),
                userStub.client());

        StepVerifier.create(service.getMe(AUTH_HEADER))
                .assertNext(me -> assertThat(me.profileReady()).isFalse())
                .verifyComplete();

        assertThat(userStub.callCount()).isEqualTo(1);
        assertThat(logAppender.list)
                .hasSize(1)
                .allSatisfy(event -> assertThat(event.getLevel()).isEqualTo(Level.ERROR));
    }

    @Test
    void getMe_whenUserServiceReturns500_degradesWithoutRetryAndLogsError() {
        SequencedStub userStub = SequencedStub.responses(status(HttpStatus.INTERNAL_SERVER_ERROR, "{}"));

        MeServiceImpl service = new MeServiceImpl(
                accountStub(HttpStatus.OK, ACCOUNT_JSON),
                userStub.client());

        StepVerifier.create(service.getMe(AUTH_HEADER))
                .assertNext(me -> assertThat(me.profileReady()).isFalse())
                .verifyComplete();

        assertThat(userStub.callCount()).isEqualTo(1);
        assertThat(logAppender.list)
                .hasSize(1)
                .allSatisfy(event -> assertThat(event.getLevel()).isEqualTo(Level.ERROR));
    }

    @Test
    void getMe_whenAuthServiceFails_propagatesErrorWithoutDegrading() {
        MeServiceImpl service = new MeServiceImpl(
                accountStub(HttpStatus.INTERNAL_SERVER_ERROR, "{}"),
                accountStub(HttpStatus.OK, PROFILE_JSON));

        StepVerifier.create(service.getMe(AUTH_HEADER))
                .expectError(WebClientResponseException.InternalServerError.class)
                .verify();
    }

    private WebClient accountStub(HttpStatus status, String jsonBody) {
        return SequencedStub.responses(status(status, jsonBody)).client();
    }

    private static StatusAndBody status(HttpStatus status, String body) {
        return new StatusAndBody(status, body);
    }

    private record StatusAndBody(HttpStatus status, String body) {
    }

    /**
     * A WebClient stub whose exchange function replays a scripted sequence of
     * status/body responses across successive calls (repeating the last entry once
     * exhausted), or fails every call with a fixed exception. Tracks how many times
     * it was called so tests can assert whether a retry happened.
     */
    private static final class SequencedStub {

        private final StatusAndBody[] responses;
        private final Throwable error;
        private final AtomicInteger callCount = new AtomicInteger(0);

        private SequencedStub(StatusAndBody[] responses, Throwable error) {
            this.responses = responses;
            this.error = error;
        }

        static SequencedStub responses(StatusAndBody... responses) {
            return new SequencedStub(responses, null);
        }

        static SequencedStub failingWith(Throwable error) {
            return new SequencedStub(null, error);
        }

        WebClient client() {
            ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
            ExchangeStrategies strategies = ExchangeStrategies.builder()
                    .codecs(configurer -> {
                        configurer.defaultCodecs().jackson2JsonDecoder(new Jackson2JsonDecoder(mapper));
                        configurer.defaultCodecs().jackson2JsonEncoder(new Jackson2JsonEncoder(mapper));
                    })
                    .build();

            ExchangeFunction exchangeFunction = request -> {
                int callIndex = callCount.getAndIncrement();
                if (error != null) {
                    return Mono.error(error);
                }
                StatusAndBody response = responses[Math.min(callIndex, responses.length - 1)];
                return Mono.just(ClientResponse.create(response.status())
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body(response.body())
                        .build());
            };

            return WebClient.builder()
                    .exchangeStrategies(strategies)
                    .exchangeFunction(exchangeFunction)
                    .build();
        }

        int callCount() {
            return callCount.get();
        }
    }
}
