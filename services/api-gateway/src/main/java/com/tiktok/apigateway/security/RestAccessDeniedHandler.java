package com.tiktok.apigateway.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
public class RestAccessDeniedHandler implements ServerAccessDeniedHandler {

    private final ObjectMapper objectMapper;

    @Override
    @SneakyThrows
    public Mono<Void> handle(ServerWebExchange exchange, AccessDeniedException ex) {
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.FORBIDDEN);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        byte[] body = objectMapper.writeValueAsBytes(
                ApiResponse.error("FORBIDDEN", "You do not have access to this resource"));
        DataBuffer buffer = response.bufferFactory().wrap(body);

        return response.writeWith(Mono.just(buffer));
    }
}
