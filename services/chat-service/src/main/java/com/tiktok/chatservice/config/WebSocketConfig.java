package com.tiktok.chatservice.config;

import com.tiktok.chatservice.websocket.JwtHandshakeInterceptor;
import com.tiktok.chatservice.websocket.UserPrincipalHandshakeHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final JwtHandshakeInterceptor jwtHandshakeInterceptor;

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
                .setAllowedOriginPatterns("*");

        registry.addEndpoint("/ws")
                .addInterceptors(jwtHandshakeInterceptor)
                .setHandshakeHandler(new UserPrincipalHandshakeHandler())
                .setAllowedOriginPatterns("*")
                .withSockJS();
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }
}
