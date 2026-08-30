package com.tiktok.mediaworker.service;

import com.tiktok.mediaworker.config.MinioProperties;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * Copies the picture an identity provider serves into our own bucket.
 *
 * <p>Why copy at all: a Facebook picture URL carries a token and stops resolving within days, so an
 * account that signed up through Facebook loses its avatar shortly after being given one. Serving
 * it from our storage also stops every viewer of every comment from telling the provider's CDN who
 * is looking at what.
 *
 * <p>The fetch is the one place in this codebase that requests a URL the app did not construct, so
 * it is fenced: https only, the host must be one this service was configured to trust, every hop of
 * a redirect chain is checked <em>before</em> it is requested, the response must actually be an
 * image, and the read stops at a byte budget rather than trusting Content-Length. Without the host
 * check a forged event would make this worker fetch whatever an attacker named — including
 * addresses reachable only from inside the cluster.
 */
@Slf4j
@Service
public class AvatarMirrorService {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    /** Facebook answers with one hop to its CDN. More than a few is a loop, not a provider. */
    private static final int MAX_REDIRECTS = 3;

    private final MinioClient minioClient;
    private final MinioProperties minioProperties;
    private final List<String> allowedHosts;
    private final long maxBytes;
    /** Package-private so the test can assert this client still refuses to follow on its own. */
    final HttpClient httpClient;

    public AvatarMirrorService(
            MinioClient minioClient,
            MinioProperties minioProperties,
            @Value("${media.avatar.allowed-hosts}") List<String> allowedHosts,
            @Value("${media.avatar.max-bytes:5242880}") long maxBytes) {
        this.minioClient = minioClient;
        this.minioProperties = minioProperties;
        this.allowedHosts = List.copyOf(allowedHosts);
        this.maxBytes = maxBytes;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                // NEVER, and the chain is walked by hand in download() instead. Letting the client
                // follow means the request to the next host is already sent by the time the
                // response comes back, so checking the final URI afterwards refuses to *read* an
                // internal address this worker has already *reached* — a blind SSRF an open
                // redirect on a trusted provider is enough to reach. The allow-list has to be
                // applied before each send, which is only possible from outside the client.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** @return where the copy lives in our storage, ready to go on the profile. */
    @SneakyThrows
    public String mirror(Long userId, String sourceUrl) {
        // Before anything else, including the "already copied?" lookup. A URL we would refuse to
        // fetch is a refusal whether or not an old copy happens to be lying around — otherwise the
        // answer to "is this source acceptable" depends on the state of the bucket.
        URI source = requireAllowed(URI.create(sourceUrl));

        String bucket = minioProperties.bucket();
        String key = MediaKeys.avatar(userId);
        String storedUrl = "%s/%s/%s".formatted(minioProperties.endpoint(), bucket, key);

        // Already copied. Every sign-in announces the same picture, and re-downloading it each time
        // would hang a provider round trip off a path that already has one.
        //
        // ponytail: it also means a picture the user later changes at the provider is never picked
        // up. Compare an ETag, or re-fetch anything older than a month, if that starts to matter —
        // the key stays fixed either way, because a profile already pointing at this URL is what
        // makes a replacement invisible to everything downstream.
        if (exists(bucket, key)) {
            log.debug("Avatar of user {} is already mirrored at {}", userId, key);
            return storedUrl;
        }

        Download download = download(source);
        minioClient.putObject(PutObjectArgs.builder()
                .bucket(bucket)
                .object(key)
                .contentType(download.contentType())
                .stream(new ByteArrayInputStream(download.body()), download.body().length, -1)
                .build());

        log.info("Mirrored the avatar of user {} into {} ({} bytes)", userId, key, download.body().length);
        return storedUrl;
    }

    @SneakyThrows
    private boolean exists(String bucket, String key) {
        try {
            minioClient.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build());
            return true;
        } catch (ErrorResponseException e) {
            // Absent is the ordinary case and the only one that may be swallowed; a permissions or
            // connectivity failure has to reach the caller so the message gets redelivered.
            if ("NoSuchKey".equals(e.errorResponse().code())) {
                return false;
            }
            throw e;
        }
    }

    /**
     * Walks the redirect chain itself, checking the allow-list before every request rather than
     * after the last one. A {@code Location} may be relative, so each hop is resolved against the
     * URI it came from before being checked.
     */
    @SneakyThrows
    private Download download(URI uri) {
        URI target = uri;

        for (int hop = 0; ; hop++) {
            URI current = requireAllowed(target);

            HttpResponse<InputStream> response = httpClient.send(
                    HttpRequest.newBuilder(current).timeout(TIMEOUT).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());

            if (!isRedirect(response.statusCode())) {
                return read(response, current);
            }

            // Closed before the next hop so the connection goes back to the pool; a redirect body
            // is never anything we want.
            response.body().close();

            if (hop == MAX_REDIRECTS) {
                throw new IllegalStateException(
                        "Avatar at %s redirected more than %d times".formatted(uri, MAX_REDIRECTS));
            }
            String location = response.headers().firstValue("location").orElseThrow(
                    () -> new IllegalStateException(
                            "Provider answered %d for the avatar at %s with no Location"
                                    .formatted(response.statusCode(), current)));
            target = current.resolve(location);
        }
    }

    private boolean isRedirect(int statusCode) {
        return statusCode == 301 || statusCode == 302 || statusCode == 303
                || statusCode == 307 || statusCode == 308;
    }

    @SneakyThrows
    private Download read(HttpResponse<InputStream> response, URI uri) {
        // Every rejection below happens inside the try, so the body stream is closed and its
        // connection returned to the pool on the way out. Throwing before opening it leaks one
        // connection per refusal, and a provider that has started answering 4xx refuses every
        // avatar until this worker runs out.
        try (InputStream body = response.body()) {
            if (response.statusCode() != 200) {
                throw new IllegalStateException(
                        "Provider answered %d for the avatar at %s".formatted(response.statusCode(), uri));
            }

            String contentType = response.headers().firstValue("content-type")
                    .orElse("").toLowerCase(Locale.ROOT);
            if (!contentType.startsWith("image/")) {
                throw new IllegalStateException("Avatar at %s is %s, not an image"
                        .formatted(uri, contentType.isEmpty() ? "untyped" : contentType));
            }

            // One byte past the budget on purpose: it is what tells a file of exactly maxBytes
            // apart from one that was truncated at the limit.
            byte[] bytes = body.readNBytes((int) Math.min(maxBytes + 1, Integer.MAX_VALUE));
            if (bytes.length > maxBytes) {
                throw new IllegalStateException(
                        "Avatar at %s is larger than the %d byte budget".formatted(uri, maxBytes));
            }
            return new Download(bytes, contentType.split(";")[0]);
        }
    }

    private URI requireAllowed(URI uri) {
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !allowedHosts.contains(uri.getHost())) {
            throw new IllegalArgumentException("Refusing to fetch an avatar from " + uri);
        }
        return uri;
    }

    private record Download(byte[] body, String contentType) {
    }
}
