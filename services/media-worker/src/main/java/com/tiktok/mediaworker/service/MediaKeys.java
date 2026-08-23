package com.tiktok.mediaworker.service;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;

/**
 * Where a video's derived objects live in the bucket. Shared by the transcode that writes them
 * and the cleanup that removes them, because the two only agree by convention — a rename on one
 * side alone leaves files behind with nothing pointing at them and nothing failing.
 */
public final class MediaKeys {

    private MediaKeys() {
    }

    /**
     * One key per user, overwritten rather than versioned: the profile stores this URL, so a fresh
     * key per copy would leave the profile pointing at the old object with nothing to update it.
     *
     * <p>The extension is a label, not a promise — whatever the provider served is stored, with its
     * own content type on the object, and browsers go by that rather than by the name.
     */
    public static String avatar(Long userId) {
        return "avatars/%d.jpg".formatted(userId);
    }

    public static String thumbnail(String videoId) {
        return "thumbnails/%s.jpg".formatted(videoId);
    }

    /** Every HLS artifact of one video sits under this prefix — playlist, segments, variants. */
    public static String hlsPrefix(String videoId) {
        return "hls/%s/".formatted(videoId);
    }

    /**
     * What viewers actually load. It sits under {@link #hlsPrefix} so the cleanup that lists that
     * prefix removes it too — the current pipeline copies the upload here instead of segmenting
     * it, and a real HLS transcode would drop its playlist and segments alongside.
     */
    public static String playback(String videoId) {
        return hlsPrefix(videoId) + "source.mp4";
    }

    public static String hlsPlaylist(String videoId) {
        return hlsPrefix(videoId) + "master.m3u8";
    }

    /**
     * The object key inside {@code bucket} that a stored URL points at. The same upload reaches
     * this service two ways and the bucket sits in a different place in each: {@code s3://} puts
     * it in the host, an {@code https} CDN URL puts it in the path. Both are matched, so the key
     * is measured from wherever the bucket is named rather than from a fixed segment count.
     *
     * <p>Empty when the URL does not name this bucket at all. The caller skips the delete in that
     * case: guessing a key here would mean deleting whatever object that guess happens to hit.
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
                return Optional.of(String.join("/", java.util.Arrays.copyOfRange(segments, i + 1, segments.length)));
            }
        }
        return Optional.empty();
    }
}
