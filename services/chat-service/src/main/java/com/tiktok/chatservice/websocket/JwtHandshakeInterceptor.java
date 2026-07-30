package com.tiktok.chatservice.websocket;

import com.tiktok.crypto.jwt.JwtProvider;
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
 */
@Component
@RequiredArgsConstructor
public class JwtHandshakeInterceptor implements HandshakeInterceptor {

    private static final String TOKEN_PARAM = "token";
    static final String USER_ID_ATTRIBUTE = "userId";

    private final JwtProvider jwtProvider;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                    WebSocketHandler wsHandler, Map<String, Object> attributes) {
        List<String> tokenParams = UriComponentsBuilder.fromUri(request.getURI())
                .build()
                .getQueryParams()
                .get(TOKEN_PARAM);
        String token = tokenParams != null && !tokenParams.isEmpty() ? tokenParams.get(0) : null;

        if (token == null || !jwtProvider.isValid(token)) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        attributes.put(USER_ID_ATTRIBUTE, jwtProvider.extractSubject(token));
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                WebSocketHandler wsHandler, Exception exception) {
        // no-op
    }
}
