package com.tiktok.apigateway.config;

import com.tiktok.apigateway.security.JwtReactiveAuthenticationManager;
import com.tiktok.apigateway.security.JwtServerAuthenticationConverter;
import com.tiktok.apigateway.security.RestAccessDeniedHandler;
import com.tiktok.apigateway.security.RestAuthenticationEntryPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * The edge rules, exercised through the real filter chain. Downstream services permit some paths
 * without a token because only other services are meant to call them; the gateway is what keeps
 * those paths off the internet.
 */
@WebFluxTest(controllers = GatewaySecurityRulesTest.Stub.class)
@Import({SecurityConfig.class, JwtServerAuthenticationConverter.class, RestAuthenticationEntryPoint.class,
        RestAccessDeniedHandler.class, GatewaySecurityRulesTest.Stub.class})
class GatewaySecurityRulesTest {

    @Autowired
    private WebTestClient client;

    @MockitoBean
    private JwtReactiveAuthenticationManager authenticationManager;

    @BeforeEach
    void signedInUser() {
        when(authenticationManager.authenticate(any())).thenReturn(Mono.just(
                new UsernamePasswordAuthenticationToken(7L, "token",
                        List.of(new SimpleGrantedAuthority("ROLE_USER")))));
    }

    @Test
    void internalUserEndpoints_areRefusedEvenWithAValidToken() {
        client.get().uri("/api/v1/users/internal/blocks?userA=1&userB=2")
                .header(HttpHeaders.AUTHORIZATION, "Bearer token")
                .exchange()
                .expectStatus().isForbidden();
    }

    /**
     * The settings endpoints ride on the same prefix rule, which is the whole reason they were put
     * there: a settings endpoint at its own path would need its own deny rule, and forgetting it
     * publishes every account's comment and DM audience. Answering 401 without a token and 403
     * with one, it never reaches user-service either way.
     */
    @Test
    void internalSettingsEndpoints_areRefusedWithAndWithoutAToken() {
        for (String uri : List.of("/api/v1/users/internal/settings?ids=1",
                "/api/v1/users/internal/friendship?userA=1&userB=2")) {
            client.get().uri(uri)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer token")
                    .exchange()
                    .expectStatus().isForbidden();
            client.get().uri(uri)
                    .exchange()
                    .expectStatus().isUnauthorized();
        }
    }

    /**
     * interaction-service's internal prefix answers without a token — notification-service asks it
     * off a Kafka event — and names the author of any comment, removed ones included.
     */
    @Test
    void internalInteractionEndpoints_areRefusedWithAndWithoutAToken() {
        String uri = "/api/v1/interactions/internal/videos/1/comments/2/author";
        client.get().uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer token")
                .exchange()
                .expectStatus().isForbidden();
        client.get().uri(uri)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    /**
     * admin-service's internal prefix hands out the moderation word list — every admin term,
     * including what is exempted. Only the services that screen text may read it, and an admin's
     * own token does not make a caller one of them.
     */
    @Test
    void internalAdminEndpoints_areRefusedWithAndWithoutAToken() {
        String uri = "/api/v1/admin/internal/blocklist";
        client.get().uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer token")
                .exchange()
                .expectStatus().isForbidden();
        client.get().uri(uri)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void ordinaryUserEndpoints_stillPassForASignedInCaller() {
        client.get().uri("/api/v1/users/42")
                .header(HttpHeaders.AUTHORIZATION, "Bearer token")
                .exchange()
                .expectStatus().isOk();
    }

    /**
     * /policy answers for every video regardless of visibility — who owns it, whether comments are
     * off — because interaction-service needs that for PRIVATE videos too. Reachable from the
     * internet it told anyone the owner of any private video id, which getById exists to hide.
     */
    @Test
    void videoPolicy_isRefusedAtTheEdge() {
        client.get().uri("/api/v1/videos/123/policy")
                .header(HttpHeaders.AUTHORIZATION, "Bearer token")
                .exchange()
                .expectStatus().isForbidden();
        client.get().uri("/api/v1/videos/123/policy")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void anOrdinaryVideo_isStillPublic() {
        client.get().uri("/api/v1/videos/123")
                .exchange()
                .expectStatus().isOk();
    }

    /**
     * The reads interaction-service leaves public itself, so a signed-out viewer sees a video's
     * counts and comments. The catch-all here answered them 401 first, whatever the service said.
     */
    @Test
    void publicInteractionReads_passWithoutAToken_butWritesDoNot() {
        for (String uri : List.of("/api/v1/interactions/videos/123/comments",
                "/api/v1/interactions/videos/123/comments/456/replies",
                "/api/v1/interactions/videos/123/counts",
                "/api/v1/interactions/videos/counts/batch?videoIds=1,2",
                "/api/v1/interactions/videos/123/like-status")) {
            client.get().uri(uri)
                    .exchange()
                    .expectStatus().isOk();
        }
        client.post().uri("/api/v1/interactions/videos/123/comments")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    /**
     * A profile opens for a signed-out viewer by id, by handle and as a batch of comment authors.
     * The id rule is numeric on purpose: /me, /search and the follower lists share the prefix and
     * one segment, and must keep answering 401 without a token.
     */
    @Test
    void profileReads_passWithoutAToken_butTheRestOfUsersDoesNot() {
        for (String uri : List.of("/api/v1/users/42", "/api/v1/users/by-username/alice",
                "/api/v1/users?ids=1,2")) {
            client.get().uri(uri)
                    .exchange()
                    .expectStatus().isOk();
        }
        for (String uri : List.of("/api/v1/users/me", "/api/v1/users/search?q=alice",
                "/api/v1/users/42/followers")) {
            client.get().uri(uri)
                    .exchange()
                    .expectStatus().isUnauthorized();
        }
    }

    /**
     * A profile's stories, a single story and the avatar rings open signed out — story-service shows
     * an anonymous viewer only PUBLIC stories of public accounts. The tray and a story's viewers
     * need a viewer.
     */
    @Test
    void storyReads_passWithoutAToken_butTheFeedAndViewersDoNot() {
        for (String uri : List.of("/api/v1/stories/users/42", "/api/v1/stories/s1",
                "/api/v1/stories/rings?userIds=1")) {
            client.get().uri(uri)
                    .exchange()
                    .expectStatus().isOk();
        }
        for (String uri : List.of("/api/v1/stories/feed?followedUserIds=1", "/api/v1/stories/s1/viewers")) {
            client.get().uri(uri)
                    .exchange()
                    .expectStatus().isUnauthorized();
        }
    }

    @RestController
    static class Stub {

        @GetMapping({"/api/v1/users/internal/blocks", "/api/v1/users/internal/settings",
                "/api/v1/users/internal/friendship", "/api/v1/users/42",
                "/api/v1/users/by-username/alice", "/api/v1/users",
                "/api/v1/videos/123/policy", "/api/v1/videos/123",
                "/api/v1/interactions/internal/videos/1/comments/2/author",
                "/api/v1/admin/internal/blocklist",
                "/api/v1/interactions/videos/123/comments",
                "/api/v1/interactions/videos/123/comments/456/replies",
                "/api/v1/interactions/videos/123/counts",
                "/api/v1/interactions/videos/counts/batch", "/api/v1/interactions/videos/123/like-status",
                "/api/v1/stories/users/42", "/api/v1/stories/s1", "/api/v1/stories/feed",
                "/api/v1/stories/rings", "/api/v1/stories/s1/viewers"})
        Mono<String> ok() {
            return Mono.just("ok");
        }

        @PostMapping("/api/v1/interactions/videos/123/comments")
        Mono<String> write() {
            return Mono.just("written");
        }
    }
}
