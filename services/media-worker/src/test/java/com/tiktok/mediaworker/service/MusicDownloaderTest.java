package com.tiktok.mediaworker.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Against a local stand-in for the catalogue's file host. */
class MusicDownloaderTest {

    private static final byte[] MP3 = {'I', 'D', '3', 4, 0, 0, 0, 0, 0, 0, 1, 2, 3};

    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();

    @TempDir
    Path work;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", exchange -> {
            requests.incrementAndGet();
            // As the real host does: an MP3 labelled text/html.
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, MP3.length);
            exchange.getResponseBody().write(MP3);
            exchange.close();
        });
        server.createContext("/page", exchange -> {
            byte[] html = "<html>gone</html>".getBytes();
            exchange.sendResponseHeaders(200, html.length);
            exchange.getResponseBody().write(html);
            exchange.close();
        });
        server.createContext("/big", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            byte[] chunk = new byte[1024];
            chunk[0] = 'I';
            chunk[1] = 'D';
            chunk[2] = '3';
            for (int i = 0; i < 100; i++) {
                exchange.getResponseBody().write(chunk);
            }
            exchange.close();
        });
        server.createContext("/gone", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.createContext("/down", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.createContext("/moved", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://169.254.169.254/latest/meta-data");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private MusicDownloader local(long maxBytes) {
        return new MusicDownloader(List.of("127.0.0.1"), maxBytes, false);
    }

    @Test
    void downloadsAnMp3WhateverItIsLabelled() throws Exception {
        Path file = local(1024).download(url("/ok"), work.resolve("music.mp3"));

        assertThat(Files.readAllBytes(file)).isEqualTo(MP3);
    }

    /** The URL rode on a topic: only the catalogue's hosts, only https, whatever the event says. */
    @Test
    void refusesAnythingButHttpsOnTheCataloguesHosts() {
        MusicDownloader real = new MusicDownloader(List.of(".jamendo.com"), 1024);

        for (String url : List.of(url("/ok"), "http://prod-1.storage.jamendo.com/x", "https://jamendo.com.evil.example/x",
                "https://evil.example/x?h=.jamendo.com", "file:///etc/passwd", "https://user@prod-1.storage.jamendo.com/x",
                "not a url")) {
            assertThatThrownBy(() -> real.download(url, work.resolve("music.mp3")))
                    .as(url).isInstanceOf(MediaRejectedException.class);
        }
        assertThat(requests).hasValue(0);
    }

    @Test
    void acceptsTheCataloguesFileHosts() {
        MusicDownloader real = new MusicDownloader(List.of(".jamendo.com"), 1024);

        assertThat(real.requireAllowed("https://prod-1.storage.jamendo.com/download/track/42/mp32/").getHost())
                .isEqualTo("prod-1.storage.jamendo.com");
        assertThat(real.requireAllowed("https://jamendo.com/x").getHost()).isEqualTo("jamendo.com");
    }

    @Test
    void anHtmlPage_isNotMusic() {
        assertThatThrownBy(() -> local(1024).download(url("/page"), work.resolve("music.mp3")))
                .isInstanceOf(MediaRejectedException.class);
    }

    @Test
    void stopsReadingPastTheCap() {
        assertThatThrownBy(() -> local(10_000).download(url("/big"), work.resolve("music.mp3")))
                .isInstanceOf(MediaRejectedException.class);
    }

    /** Gone is final; a failing host may come back, so that is left to the retries. */
    @Test
    void goneIsRejected_downIsRetryable() {
        assertThatThrownBy(() -> local(1024).download(url("/gone"), work.resolve("music.mp3")))
                .isInstanceOf(MediaRejectedException.class);
        assertThatThrownBy(() -> local(1024).download(url("/down"), work.resolve("music.mp3")))
                .isInstanceOf(IOException.class);
    }

    @Test
    void neverFollowsARedirect() {
        assertThatThrownBy(() -> local(1024).download(url("/moved"), work.resolve("music.mp3")))
                .isInstanceOf(MediaRejectedException.class);
    }
}
