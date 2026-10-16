package com.tiktok.videoservice.config;

import com.sun.net.httpserver.HttpServer;
import com.tiktok.common.http.InternalApiToken;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DownstreamRestClientConfigTest {

    private static final InternalApiToken TOKEN = InternalApiToken.from(new MockEnvironment()
            .withProperty("INTERNAL_API_TOKEN", "internal-token-for-tests-0123456789abcdef"));

    private HttpServer hungServer;

    /** Everything these clients call sits on user-service's /internal/ prefix, which checks it. */
    @Test
    void bothClientsCarryTheInternalToken() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        hungServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        hungServer.createContext("/", exchange -> {
            seen.set(exchange.getRequestHeaders().getFirst(InternalApiToken.HEADER));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        hungServer.start();
        String uri = "http://localhost:" + hungServer.getAddress().getPort();
        DownstreamRestClientConfig config = new DownstreamRestClientConfig();

        config.userServiceRestClient(uri, 1_000, TOKEN).get().uri("/x").retrieve().toBodilessEntity();
        assertThat(seen.getAndSet(null)).isEqualTo(TOKEN.value());

        config.userSettingsRestClient(uri, 1_000, TOKEN).get().uri("/x").retrieve().toBodilessEntity();
        assertThat(seen.get()).isEqualTo(TOKEN.value());
    }

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
                .userServiceRestClient("http://localhost:" + hungServer.getAddress().getPort(), 300, TOKEN);

        long started = System.nanoTime();
        assertThatThrownBy(() -> client.get().uri("/anything").retrieve().toBodilessEntity())
                .isInstanceOf(ResourceAccessException.class);
        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(2_000);
    }

    /**
     * The comment-audience lookup sits inside {@code /policy}, which interaction- and
     * notification-service give one second. On the same second as its callers, a slow
     * user-service timed the whole answer out at the caller, and the caller then failed open on
     * the comments switch, owner deletes and notifications as well as the audience. The deployed
     * value has to give up with most of that second still left.
     */
    @Test
    void theSettingsLookupGivesUpWellInsideTheSecondPolicysCallersAllow() throws Exception {
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

        StandardEnvironment environment = new StandardEnvironment();
        new YamlPropertySourceLoader().load("app", new ClassPathResource("application.yml"))
                .forEach(environment.getPropertySources()::addLast);
        long configured = environment.getRequiredProperty("downstream.settings-timeout-millis", Long.class);

        RestClient client = new DownstreamRestClientConfig()
                .userSettingsRestClient("http://localhost:" + hungServer.getAddress().getPort(), configured, TOKEN);

        long started = System.nanoTime();
        assertThatThrownBy(() -> client.get().uri("/anything").retrieve().toBodilessEntity())
                .isInstanceOf(ResourceAccessException.class);
        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(POLICY_CALLER_TIMEOUT_MILLIS / 2);
    }

    /** interaction-service's and notification-service's {@code downstream.timeout-millis} default. */
    private static final long POLICY_CALLER_TIMEOUT_MILLIS = 1_000;
}
