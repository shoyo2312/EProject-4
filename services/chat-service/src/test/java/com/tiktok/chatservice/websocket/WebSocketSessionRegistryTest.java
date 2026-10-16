package com.tiktok.chatservice.websocket;

import com.tiktok.crypto.jwt.JwtProvider;
import com.tiktok.security.jwt.RevokedTokenChecker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebSocketSessionRegistryTest {

    private final JwtProvider jwtProvider = new JwtProvider("change-this-secret-in-production-please-0123456789");

    @Mock
    private RevokedTokenChecker revokedTokenChecker;

    private WebSocketSession openSession(String id, Map<String, Object> attributes) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        when(session.getAttributes()).thenReturn(attributes);
        return session;
    }

    @Test
    void sweepLeavesAGuestOpenAndClosesAnInvalidToken() throws Exception {
        WebSocketSessionRegistry registry = new WebSocketSessionRegistry(jwtProvider, revokedTokenChecker);
        WebSocketSession guest = openSession("guest", new HashMap<>());
        WebSocketSession invalid = openSession("invalid",
                new HashMap<>(Map.of(JwtHandshakeInterceptor.TOKEN_ATTRIBUTE, "garbage")));
        registry.register(guest);
        registry.register(invalid);

        registry.closeInvalidSessions();

        verify(guest, never()).close(any(CloseStatus.class));
        verify(invalid).close(CloseStatus.POLICY_VIOLATION);
    }
}
