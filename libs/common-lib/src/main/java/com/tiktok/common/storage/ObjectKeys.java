package com.tiktok.common.storage;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Optional;

/**
 * Reads the object key out of a stored media URL.
 *
 * <p>Shared because two services act on the same URL and have to agree on what it points at:
 * video-service decides whose upload it is when a video is published, and media-worker fetches
 * and later deletes the object. Each used to parse it its own way — one read the segment after
 * the first {@code raw}, the other everything after the first bucket-named segment — so
 * {@code https://cdn/raw/1/video-media/raw/2/x.mp4} was user 1's upload to the ownership check
 * and user 2's file to the transcode. A check is only as good as its agreement with the code that
 * acts on the answer.
 */
public final class ObjectKeys {

    private ObjectKeys() {
    }

    /**
     * The object key inside {@code bucket} that a stored URL points at. The same upload arrives
     * two ways and the bucket sits in a different place in each: {@code s3://} puts it in the
     * host, an {@code https} CDN URL puts it in the path. Both are matched, so the key is measured
     * from wherever the bucket is named rather than from a fixed segment count.
     *
     * <p>Empty when the URL does not name this bucket at all. Callers treat that as "not ours":
     * guessing a key here would mean reading or deleting whatever object that guess happens to hit.
     */
    public static Optional<String> objectKey(String url, String bucket) {
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }

        URI uri;
        try {
            // normalize() first: java.net.URI keeps dot segments, so a key read straight off an
            // unnormalized path is not the key the storage layer would address.
            uri = new URI(url).normalize();
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
        String path = uri.getPath();
        if (path == null) {
            return Optional.empty();
        }

        // s3://video-media/raw/7/x.mp4 — the bucket is the authority and the whole path is the key.
        if (bucket.equals(uri.getHost())) {
            String key = path.startsWith("/") ? path.substring(1) : path;
            return key.isEmpty() ? Optional.empty() : Optional.of(key);
        }

        String[] segments = path.split("/");
        for (int i = 0; i < segments.length - 1; i++) {
            if (bucket.equals(segments[i])) {
                return Optional.of(String.join("/", Arrays.copyOfRange(segments, i + 1, segments.length)));
            }
        }
        return Optional.empty();
    }
}
