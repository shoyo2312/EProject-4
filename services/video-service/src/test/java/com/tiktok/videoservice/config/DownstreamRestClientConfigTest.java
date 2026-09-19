package com.tiktok.videoservice.config;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DownstreamRestClientConfigTest {

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
                .userServiceRestClient("http://localhost:" + hungServer.getAddress().getPort(), 300);

        long started = System.nanoTime();
        assertThatThrownBy(() -> client.get().uri("/anything").retrieve().toBodilessEntity())
                .isInstanceOf(ResourceAccessException.class);
        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(2_000);
    }
}
