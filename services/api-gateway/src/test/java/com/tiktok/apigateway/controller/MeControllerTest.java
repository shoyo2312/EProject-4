package com.tiktok.apigateway.controller;

import com.tiktok.apigateway.dto.MeResponse;
import com.tiktok.apigateway.service.MeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests the controller in isolation (bindToController, no full Spring context / security
 * filter chain) — only verifies HTTP wiring: header forwarding into the service call and
 * response envelope shape.
 */
@ExtendWith(MockitoExtension.class)
class MeControllerTest {

    @Mock
    private MeService meService;

    @Test
    void getMe_forwardsAuthorizationHeaderAndWrapsResultInApiResponse() {
        MeResponse meResponse = new MeResponse(
                1L, "alice", "alice@example.com", "USER", "ACTIVE", Instant.parse("2024-01-01T00:00:00Z"),
                "Alice A", "hello", "https://example.com/avatar.png", 10L, 5L, true);
        when(meService.getMe(anyString())).thenReturn(Mono.just(meResponse));

        WebTestClient client = WebTestClient.bindToController(new MeController(meService)).build();

        client.get().uri("/api/v1/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.success").isEqualTo(true)
                .jsonPath("$.data.id").isEqualTo(1)
                .jsonPath("$.data.username").isEqualTo("alice")
                .jsonPath("$.data.displayName").isEqualTo("Alice A")
                .jsonPath("$.data.profileReady").isEqualTo(true);

        ArgumentCaptor<String> headerCaptor = ArgumentCaptor.forClass(String.class);
        verify(meService).getMe(headerCaptor.capture());
        assertThat(headerCaptor.getValue()).isEqualTo("Bearer test-token");
    }

    @Test
    void getMe_whenProfileNotReady_returnsNullProfileFieldsButStillOk() {
        MeResponse meResponse = new MeResponse(
                1L, "alice", "alice@example.com", "USER", "ACTIVE", Instant.parse("2024-01-01T00:00:00Z"),
                null, null, null, null, null, false);
        when(meService.getMe(anyString())).thenReturn(Mono.just(meResponse));

        WebTestClient client = WebTestClient.bindToController(new MeController(meService)).build();

        client.get().uri("/api/v1/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.profileReady").isEqualTo(false)
                .jsonPath("$.data.displayName").doesNotExist();
    }
}
