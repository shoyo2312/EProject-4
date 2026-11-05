package com.tiktok.videoservice.client;

import com.sun.net.httpserver.HttpServer;
import com.tiktok.videoservice.config.JamendoProperties;
import com.tiktok.videoservice.exception.MusicUnavailableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Against a local stand-in for api.jamendo.com, answering what the real one answers. */
class JamendoClientTest {

    private HttpServer server;
    private final Deque<String> answers = new ArrayDeque<>();
    private final List<String> queries = new ArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v3.0/tracks/", exchange -> {
            queries.add(exchange.getRequestURI().getRawQuery());
            byte[] body = (answers.size() > 1 ? answers.poll() : answers.peek()).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private JamendoClient client(String clientId) {
        return new JamendoClient(new JamendoProperties(clientId,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v3.0", Duration.ofSeconds(2)));
    }

    private static String page(String... tracks) {
        return """
                {"headers":{"status":"success","code":0,"results_count":%d},"results":[%s]}
                """.formatted(tracks.length, String.join(",", tracks));
    }

    private static String track(String id, String licence, boolean downloadable) {
        return """
                {"id":"%s","name":"Song %s","duration":95,"artist_name":"Artist","audio":"https://stream/%s",
                 "audiodownload":"https://prod-1.storage.jamendo.com/download/track/%s/mp32/",
                 "audiodownload_allowed":%s,"image":"https://img/%s","license_ccurl":"%s",
                 "shareurl":"https://www.jamendo.com/track/%s"}
                """.formatted(id, id, id, id, downloadable, id, licence, id);
    }

    @Test
    void search_readsTheTracksAndAsksWithTheClientIdAndFilters() {
        answers.add(page(track("1", "http://creativecommons.org/licenses/by-sa/3.0/", true)));

        List<MusicTrack> found = client("abc123").search("chill vibes", 20, 10);

        assertThat(found).singleElement().satisfies(t -> {
            assertThat(t.id()).isEqualTo("1");
            assertThat(t.title()).isEqualTo("Song 1");
            assertThat(t.durationSeconds()).isEqualTo(95);
            assertThat(t.downloadUrl()).isEqualTo("https://prod-1.storage.jamendo.com/download/track/1/mp32/");
            assertThat(t.licenseUrl()).contains("by-sa");
        });
        assertThat(queries.getFirst())
                .contains("client_id=abc123", "search=chill", "offset=20", "limit=10", "ccnd=false",
                        "audioformat=mp32");
    }

    @Test
    void search_withoutWords_asksForThePopularOnes() {
        answers.add(page(track("1", "http://creativecommons.org/licenses/by/3.0/", true)));

        client("abc123").search(" ", 0, 20);

        assertThat(queries.getFirst()).contains("order=popularity_total").doesNotContain("search=");
    }

    /**
     * A NoDerivatives licence forbids putting the music to a picture, and a track whose artist
     * allows no download has no file for the worker — neither is offered, whatever the query.
     */
    @Test
    void tracksAVideoMayNotUse_areDropped() {
        answers.add(page(
                track("1", "http://creativecommons.org/licenses/by-nc-nd/3.0/", true),
                track("2", "http://creativecommons.org/licenses/by/3.0/", false),
                track("3", "http://creativecommons.org/licenses/by-nc/3.0/", true)));

        assertThat(client("abc123").search("x", 0, 10)).extracting(MusicTrack::id).containsExactly("3");
    }

    /** The catalogue sometimes answers a query with nothing that the same query returns a moment later. */
    @Test
    void anEmptyAnswer_isAskedAgainBeforeItIsBelieved() {
        answers.add(page());
        answers.add(page());
        answers.add(page(track("7", "http://creativecommons.org/licenses/by/3.0/", true)));

        assertThat(client("abc123").find("7")).map(MusicTrack::id).contains("7");
        assertThat(queries).hasSize(3);
    }

    @Test
    void find_ofSomethingThatStaysEmpty_isEmptyAfterThreeTries() {
        answers.add(page());

        assertThat(client("abc123").find("404")).isEmpty();
        assertThat(queries).hasSize(3);
    }

    @Test
    void aRefusal_isUnavailableNotEmpty() {
        answers.add("""
                {"headers":{"status":"failed","code":5,"error_message":"Your credential is not authorized."},"results":[]}
                """);

        assertThatThrownBy(() -> client("wrong").search("x", 0, 10)).isInstanceOf(MusicUnavailableException.class);
    }

    @Test
    void withoutAClientId_musicIsOff() {
        assertThatThrownBy(() -> client(" ").search("x", 0, 10)).isInstanceOf(MusicUnavailableException.class);
        assertThat(queries).isEmpty();
    }

    @Test
    void anUnreachableCatalogue_isUnavailable() {
        server.stop(0);

        assertThatThrownBy(() -> client("abc123").search("x", 0, 10)).isInstanceOf(MusicUnavailableException.class);
    }
}
