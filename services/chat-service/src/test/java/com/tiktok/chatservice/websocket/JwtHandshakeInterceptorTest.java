package com.tiktok.chatservice.websocket;

import com.tiktok.crypto.jwt.JwtProvider;
import com.tiktok.security.jwt.RevokedTokenChecker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class JwtHandshakeInterceptorTest {

    private final JwtProvider jwtProvider = new JwtProvider("change-this-secret-in-production-please-0123456789");

    @Mock
    private RevokedTokenChecker revokedTokenChecker;

    private Map<String, Object> handshakeWith(Map<String, Object> claims) {
        Map<String, Object> all = new HashMap<>(claims);
        all.put(JwtProvider.CLAIM_TOKEN_TYPE, JwtProvider.TOKEN_TYPE_ACCESS);
        String token = jwtProvider.generateToken("42", all, 60_000);

        MockHttpServletRequest servlet = new MockHttpServletRequest("GET", "/ws");
        servlet.setQueryString("token=" + token);
        Map<String, Object> attributes = new HashMap<>();

        boolean accepted = new JwtHandshakeInterceptor(jwtProvider, revokedTokenChecker).beforeHandshake(
                new ServletServerHttpRequest(servlet),
                new ServletServerHttpResponse(new MockHttpServletResponse()),
                null, attributes);

        assertThat(accepted).isTrue();
        return attributes;
    }

    @Test
    void keepsTheRoleForTheSubscriptionCheck() {
        assertThat(handshakeWith(Map.of("role", "ADMIN")))
                .containsEntry(JwtHandshakeInterceptor.ROLE_ATTRIBUTE, "ADMIN")
                .containsEntry(JwtHandshakeInterceptor.USER_ID_ATTRIBUTE, "42");
    }

    @Test
    void aTokenWithoutARoleLeavesNone() {
        assertThat(handshakeWith(Map.of())).doesNotContainKey(JwtHandshakeInterceptor.ROLE_ATTRIBUTE);
    }

    @Test
    void noTokenOpensAGuestSession() {
        Map<String, Object> attributes = new HashMap<>();

        boolean accepted = new JwtHandshakeInterceptor(jwtProvider, revokedTokenChecker).beforeHandshake(
                new ServletServerHttpRequest(new MockHttpServletRequest("GET", "/ws")),
                new ServletServerHttpResponse(new MockHttpServletResponse()),
                null, attributes);

        assertThat(accepted).isTrue();
        assertThat(attributes).isEmpty();
    }

    /** Not downgraded to a guest: the client refreshes on the 401 and comes back as itself. */
    @Test
    void aBadTokenIsRefused() {
        MockHttpServletRequest servlet = new MockHttpServletRequest("GET", "/ws");
        servlet.setQueryString("token=garbage");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean accepted = new JwtHandshakeInterceptor(jwtProvider, revokedTokenChecker).beforeHandshake(
                new ServletServerHttpRequest(servlet), new ServletServerHttpResponse(response),
                null, new HashMap<>());

        assertThat(accepted).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }
}
