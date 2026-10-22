package com.tiktok.notificationservice.client;

import com.tiktok.common.response.ApiResponse;
import org.springframework.web.client.HttpClientErrorException;

/**
 * Reads the {@link ApiResponse} envelope our services answer failures with, for a client that has
 * to tell its target's own error from the same status coming from somewhere else.
 */
final class ApiErrors {

    private ApiErrors() {
    }

    /**
     * Whether the failed call was answered with an {@link ApiResponse} carrying exactly {@code code}.
     * The status alone proves nothing: a 404 is also what a base URL that lands on another service,
     * a path that moved in a deploy or a proxy answers. A body that cannot be read (HTML, JSON cut
     * short, nothing at all) is not the owning service's answer either, so it is false, not thrown.
     */
    static boolean carriesCode(HttpClientErrorException error, String code) {
        try {
            ApiResponse<?> body = error.getResponseBodyAs(ApiResponse.class);
            return body != null && code.equals(body.code());
        } catch (RuntimeException unreadable) {
            return false;
        }
    }
}
