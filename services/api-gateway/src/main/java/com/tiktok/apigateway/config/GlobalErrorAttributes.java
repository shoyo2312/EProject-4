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
        Object message = defaults.get("message");

        return Map.of(
                "success", false,
                "code", codeFor(status),
                "message", message instanceof String text && !text.isBlank() ? text : "Unexpected gateway error",
                "timestamp", Instant.now().toString()
        );
    }

    /**
     * {@code HttpStatus.valueOf} throws on a code the enum does not know, and this method is the one
     * building the error body — so a downstream answering 520, or any other status outside the
     * standard set, replaced its own error with an IllegalArgumentException raised inside the
     * handler meant to report it. {@code resolve} answers null instead, and the number survives
     * either way: an unnamed status is still worth telling the client about.
     */
    private static String codeFor(int status) {
        HttpStatus resolved = HttpStatus.resolve(status);
        return resolved != null ? resolved.name() : "HTTP_" + status;
    }
}
