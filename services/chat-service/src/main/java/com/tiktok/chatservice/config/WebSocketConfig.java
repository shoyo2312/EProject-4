package com.tiktok.chatservice.config;

import com.tiktok.chatservice.websocket.JwtHandshakeInterceptor;
import com.tiktok.chatservice.websocket.StompSubscriptionInterceptor;
import com.tiktok.chatservice.websocket.UserPrincipalHandshakeHandler;
import com.tiktok.chatservice.websocket.WebSocketSessionRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;

@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final JwtHandshakeInterceptor jwtHandshakeInterceptor;
    private final StompSubscriptionInterceptor stompSubscriptionInterceptor;
    private final WebSocketSessionRegistry sessionRegistry;

    /**
     * The origins allowed to open a socket. A wildcard here would let any page a logged-in user
     * visits open an authenticated connection with a token it can read out of the URL.
     */
    @Value("${websocket.allowed-origins}")
    private String[] allowedOrigins;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Two registrations of the same path, deliberately. The first is a plain
        // WebSocket endpoint, which is what a mobile client connects to; the second adds
        // the SockJS fallback URLs under /ws/** for browsers that cannot hold a socket
        // open. Only .withSockJS() registers those, and it does not leave a raw endpoint
        // behind — a phone talking plain STOMP-over-WebSocket would have nothing to reach.
        registry.addEndpoint("/ws")
                .addInterceptors(jwtHandshakeInterceptor)
                .setHandshakeHandler(new UserPrincipalHandshakeHandler())
                .setAllowedOriginPatterns(allowedOrigins);

        registry.addEndpoint("/ws")
                .addInterceptors(jwtHandshakeInterceptor)
                .setHandshakeHandler(new UserPrincipalHandshakeHandler())
                .setAllowedOriginPatterns(allowedOrigins)
                .withSockJS();
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(stompSubscriptionInterceptor);
    }

    /**
     * Hands every open socket to {@link WebSocketSessionRegistry}, which is the only place that
     * holds a handle able to close one when its token stops being valid.
     */
    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.addDecoratorFactory(handler -> new WebSocketHandlerDecorator(handler) {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                sessionRegistry.register(session);
                super.afterConnectionEstablished(session);
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
                sessionRegistry.unregister(session);
                super.afterConnectionClosed(session, status);
            }
        });
    }
}
