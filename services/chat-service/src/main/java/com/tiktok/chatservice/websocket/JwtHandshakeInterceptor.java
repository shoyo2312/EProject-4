package com.tiktok.chatservice.websocket;

import com.tiktok.crypto.jwt.JwtProvider;
import com.tiktok.security.jwt.RevokedTokenChecker;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.Map;

/**
 * Authenticates the WebSocket handshake. Browsers cannot attach an Authorization header to
 * a SockJS/WebSocket handshake request, so the access token travels as a "token" query
 * param instead — resolved here into the "userId" handshake attribute that
 * {@link UserPrincipalHandshakeHandler} turns into the STOMP session's Principal.
 *
 * <p>Revocation is checked here as well as on every REST call, because a socket that is
 * authenticated once outlives the check: a banned user whose sessions were killed by
 * SessionRevoker would otherwise keep receiving frames on an already-open connection. The token
 * itself is kept in the session attributes so {@link WebSocketSessionRegistry} can re-run both
 * checks for the life of the connection.
 *
 * <p>No token at all is a guest: the socket opens with no Principal, which
 * {@link StompSubscriptionInterceptor} holds to the public topics — a signed-out viewer's counts
 * and comments move live too. A token that fails is still a 401, not a quiet downgrade to guest,
 * the gateway's rule: the client refreshes and reconnects as itself rather than losing its own
 * queues without a word.
 */
@Component
@RequiredArgsConstructor
public class JwtHandshakeInterceptor implements HandshakeInterceptor {

    private static final String TOKEN_PARAM = "token";
    static final String USER_ID_ATTRIBUTE = "userId";
    static final String TOKEN_ATTRIBUTE = "accessToken";
    static final String ROLE_ATTRIBUTE = "role";
    private static final String ROLE_CLAIM = "role";

    private final JwtProvider jwtProvider;
    private final RevokedTokenChecker revokedTokenChecker;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                    WebSocketHandler wsHandler, Map<String, Object> attributes) {
        List<String> tokenParams = UriComponentsBuilder.fromUri(request.getURI())
                .build()
                .getQueryParams()
                .get(TOKEN_PARAM);
        String token = tokenParams != null && !tokenParams.isEmpty() ? tokenParams.get(0) : null;
        if (token == null) {
            return true;
        }

        // One signature check for the whole handshake; it used to verify the same token four times.
        Claims claims = jwtProvider.parseAccessToken(token);
        if (claims == null || revokedTokenChecker.isRevoked(claims)) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        attributes.put(USER_ID_ATTRIBUTE, claims.getSubject());
        attributes.put(TOKEN_ATTRIBUTE, token);
        Object role = claims.get(ROLE_CLAIM);
        if (role != null) {
            attributes.put(ROLE_ATTRIBUTE, role.toString());
        }
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                WebSocketHandler wsHandler, Exception exception) {
        // no-op
    }
}
