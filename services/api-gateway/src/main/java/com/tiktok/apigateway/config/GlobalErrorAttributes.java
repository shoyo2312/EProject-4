package com.tiktok.apigateway.config;

import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.web.reactive.error.DefaultErrorAttributes;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;

import java.time.Instant;
import java.util.Map;

/**
 * Reshapes Spring Cloud Gateway's default error body (e.g. downstream service unreachable,
 * 404 for unmatched routes) into the same ApiResponse envelope every other service returns,
 * so gateway errors aren't a special case for API consumers.
 */
@Component
public class GlobalErrorAttributes extends DefaultErrorAttributes {

    @Override
    public Map<String, Object> getErrorAttributes(ServerRequest request, ErrorAttributeOptions options) {
        Map<String, Object> defaults = super.getErrorAttributes(request, options);
        int status = (int) defaults.getOrDefault("status", HttpStatus.INTERNAL_SERVER_ERROR.value());
        String message = (String) defaults.getOrDefault("message", "Unexpected gateway error");

        return Map.of(
                "success", false,
                "code", HttpStatus.valueOf(status).name(),
                "message", message,
                "timestamp", Instant.now().toString()
        );
    }
}
