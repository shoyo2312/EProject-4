package com.tiktok.interactionservice.config;

import com.sun.net.httpserver.HttpServer;
import com.tiktok.common.http.InternalApiToken;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DownstreamRestClientConfigTest {

    private static final InternalApiToken TOKEN = InternalApiToken.from(new MockEnvironment()
            .withProperty("INTERNAL_API_TOKEN", "internal-token-for-tests-0123456789abcdef"));

    private HttpServer hungServer;

    @AfterEach
    void stop() {
        hungServer.stop(0);
    }

    /**
     * The client had no timeout at all, so a downstream that accepted the connection and never
     * answered held the request thread for as long as it liked — a few of those and the whole
     * service stops serving.
     */
    @Test
    void aDownstreamThatNeverAnswers_isGivenUpOnQuickly() throws Exception {
        hungServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        hungServer.createContext("/", exchange -> {
            try {
                Thread.sleep(2_500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        hungServer.start();

        RestClient client = new DownstreamRestClientConfig()
                .videoServiceRestClient("http://localhost:" + hungServer.getAddress().getPort(), 300, TOKEN);

        long started = System.nanoTime();
        assertThatThrownBy(() -> client.get().uri("/anything").retrieve().toBodilessEntity())
                .isInstanceOf(ResourceAccessException.class);
        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(2_000);
    }
}
