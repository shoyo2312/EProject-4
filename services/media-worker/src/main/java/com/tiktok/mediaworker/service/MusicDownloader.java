package com.tiktok.mediaworker.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * Fetches the music catalogue's track for an edit. The URL came from video-service, which took it
 * from the catalogue — but it travels on a topic, so it is checked again here before this worker
 * opens a connection anywhere: https, on a catalogue host, no redirects followed, and no more
 * bytes than any song needs. What arrives must start like an MP3: the catalogue labels its files
 * text/html, so the header is all there is to go on, and ffmpeg is then told the format rather
 * than left to guess it from a stranger's bytes.
 */
@Slf4j
@Component
public class MusicDownloader {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(90);

    private final List<String> allowedHostSuffixes;
    private final long maxBytes;
    private final boolean requireHttps;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    // The one Spring uses; the other is for tests.
    @Autowired
    public MusicDownloader(
            @Value("${media.music.allowed-host-suffixes}") List<String> allowedHostSuffixes,
            @Value("${media.music.max-bytes}") long maxBytes) {
        this(allowedHostSuffixes, maxBytes, true);
    }

    /** For tests against a local plain-http stand-in. */
    MusicDownloader(List<String> allowedHostSuffixes, long maxBytes, boolean requireHttps) {
        this.allowedHostSuffixes = allowedHostSuffixes.stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();
        this.maxBytes = maxBytes;
        this.requireHttps = requireHttps;
    }

    /**
     * @throws MediaRejectedException for a URL this worker will not fetch, a track that is gone,
     *                                too large, or not an MP3 — retrying changes none of those
     * @throws IOException            for the catalogue being unreachable or failing, which a retry may cure
     */
    public Path download(String url, Path target) throws IOException, InterruptedException {
        URI uri = requireAllowed(url);
        HttpResponse<InputStream> response = httpClient.send(
                HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            int status = response.statusCode();
            if (status >= 500 || status == 429) {
                throw new IOException("Music catalogue answered " + status + " for " + uri);
            }
            if (status != 200) {
                throw new MediaRejectedException("The video's music is no longer available.");
            }
            long declared = response.headers().firstValueAsLong("Content-Length").orElse(-1);
            if (declared > maxBytes) {
                throw new MediaRejectedException("The video's music is too large to use.");
            }
            try (OutputStream out = Files.newOutputStream(target)) {
                byte[] buffer = new byte[64 * 1024];
                long total = 0;
                for (int read; (read = body.read(buffer)) != -1; ) {
                    total += read;
                    if (total > maxBytes) {
                        throw new MediaRejectedException("The video's music is too large to use.");
                    }
                    out.write(buffer, 0, read);
                }
            }
        }
        requireMp3(target);
        return target;
    }

    URI requireAllowed(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new MediaRejectedException("The video's music has no usable address.");
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        boolean schemeOk = "https".equalsIgnoreCase(uri.getScheme())
                || (!requireHttps && "http".equalsIgnoreCase(uri.getScheme()));
        boolean hostOk = allowedHostSuffixes.stream()
                .anyMatch(suffix -> host.equals(suffix.startsWith(".") ? suffix.substring(1) : suffix)
                        || (suffix.startsWith(".") && host.endsWith(suffix)));
        if (!schemeOk || !hostOk || uri.getUserInfo() != null) {
            log.warn("Refusing to fetch music from {}", uri);
            throw new MediaRejectedException("The video's music is not from the music catalogue.");
        }
        return uri;
    }

    /** An ID3 tag, or an MPEG audio frame's sync bits, in the first bytes. */
    static void requireMp3(Path file) throws IOException {
        byte[] head;
        try (InputStream in = Files.newInputStream(file)) {
            head = in.readNBytes(3);
        }
        boolean id3 = head.length == 3 && head[0] == 'I' && head[1] == 'D' && head[2] == '3';
        boolean frame = head.length >= 2 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xE0) == 0xE0;
        if (!id3 && !frame) {
            throw new MediaRejectedException("The video's music is not an MP3.");
        }
    }
}
