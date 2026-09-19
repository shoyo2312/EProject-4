package com.tiktok.apigateway.config;

import com.tiktok.apigateway.security.JwtReactiveAuthenticationManager;
import com.tiktok.apigateway.security.JwtServerAuthenticationConverter;
import com.tiktok.apigateway.security.RestAccessDeniedHandler;
import com.tiktok.apigateway.security.RestAuthenticationEntryPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
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

    @MockBean
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

    @RestController
    static class Stub {

        @GetMapping({"/api/v1/users/internal/blocks", "/api/v1/users/42",
                "/api/v1/videos/123/policy", "/api/v1/videos/123"})
        Mono<String> ok() {
            return Mono.just("ok");
        }
    }
}
