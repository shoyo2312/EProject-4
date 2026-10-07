package com.tiktok.videoservice.config;

import com.tiktok.common.http.PooledRequestFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * video-service's outbound calls, all to user-service: FRIENDS visibility needs to know whether a
 * viewer and an owner are mutual followers, a single-video read whether either has blocked the
 * other, and {@code /policy} the owner's comment audience. user-service is the only place that
 * knows any of them. Mirrors interaction-service's {@code DownstreamRestClientConfig}.
 */
@Configuration
public class DownstreamRestClientConfig {

    @Bean
    public RestClient userServiceRestClient(@Value("${downstream.user-service-uri}") String userServiceUri,
                                            @Value("${downstream.timeout-millis:1000}") long timeoutMillis) {
        return client(userServiceUri, timeoutMillis);
    }

    /**
     * The comment-audience lookup behind {@code /policy}, on a timeout of its own. {@code /policy}
     * is what interaction-service and notification-service ask on every comment, comment listing,
     * watch report and notification, and they hold the whole answer to one second. With this call
     * on that same second, a slow user-service made {@code /policy} itself time out at the caller —
     * and a caller that gets no answer fails open on everything in it: the per-video comments
     * switch, the owner's right to delete comments on their video, the probed duration, the owner
     * a notification goes to. The audience fails open on its own anyway, so giving up on it early
     * loses nothing the caller would not have lost, and keeps the rest of the answer arriving.
     */
    @Bean
    public RestClient userSettingsRestClient(@Value("${downstream.user-service-uri}") String userServiceUri,
                                             @Value("${downstream.settings-timeout-millis:250}") long timeoutMillis) {
        return client(userServiceUri, timeoutMillis);
    }

    private static RestClient client(String baseUrl, long timeoutMillis) {
        // Without a timeout the JDK waits forever, so a downstream that accepts the connection
        // and never answers holds a request thread per call until the pool runs dry.
        Duration timeout = Duration.ofMillis(timeoutMillis);
        return RestClient.builder().baseUrl(baseUrl)
                .requestFactory(PooledRequestFactory.create(timeout, timeout)).build();
    }
}
