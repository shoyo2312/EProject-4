package com.tiktok.chatservice.websocket;

import com.tiktok.crypto.jwt.JwtProvider;
import com.tiktok.security.jwt.RevokedTokenChecker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every open socket on this instance, so the token that opened it can be re-checked.
 *
 * <p>The handshake authenticates once and the connection then lives for as long as the client
 * keeps it. Without this sweep an access token's 15-minute TTL and the revocation blacklist both
 * stop applying the moment the socket is up — a logged-out or banned user keeps their live feed
 * indefinitely, which is exactly what SessionRevoker exists to prevent.
 *
 * <p>Per instance, like the subscription trackers: a socket can only be closed by the process
 * holding it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketSessionRegistry {

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    private final JwtProvider jwtProvider;
    private final RevokedTokenChecker revokedTokenChecker;

    public void register(WebSocketSession session) {
        sessions.put(session.getId(), session);
    }

    public void unregister(WebSocketSession session) {
        sessions.remove(session.getId());
    }

    /**
     * Closes every socket whose token has expired or been revoked. The window is the sweep
     * interval, not zero — same shape as the REST side, where a revoked token stops working on
     * the next request rather than mid-request.
     */
    @Scheduled(fixedDelayString = "${websocket.session-sweep-millis}")
    public void closeInvalidSessions() {
        // Dropped from the map only once the socket is actually gone. Forgetting a session whose
        // close threw would leave it open with nothing left to re-check it — the one outcome this
        // sweep exists to prevent.
        sessions.values().removeIf(session -> {
            if (!session.isOpen()) {
                return true;
            }
            if (isStillAuthenticated(session)) {
                return false;
            }
            return close(session);
        });
    }

    private boolean isStillAuthenticated(WebSocketSession session) {
        Object token = session.getAttributes().get(JwtHandshakeInterceptor.TOKEN_ATTRIBUTE);
        if (!(token instanceof String accessToken) || !jwtProvider.isValidAccessToken(accessToken)) {
            return false;
        }
        return !revokedTokenChecker.isRevoked(jwtProvider.extractClaims(accessToken));
    }

    /** @return true if the socket is closed and can be forgotten, false to retry next sweep. */
    private boolean close(WebSocketSession session) {
        try {
            session.close(CloseStatus.POLICY_VIOLATION);
            return true;
        } catch (IOException | RuntimeException e) {
            log.warn("Could not close session {}, retrying next sweep: {}", session.getId(), e.getMessage());
            return !session.isOpen();
        }
    }
}
