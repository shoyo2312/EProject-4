package com.tiktok.mediaworker.service;

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
     * <p>No extension, unlike the derived keys below: those name bytes this worker produced in a
     * format it chose, while an avatar is whatever the provider served — or, on the same key, what
     * a user uploaded through user-service. A ".jpg" on it would be a claim about the bytes that
     * nothing checks and both writers routinely break. The content type sits on the object.
     */
    public static String avatar(Long userId) {
        return "avatars/%d".formatted(userId);
    }

    public static String thumbnail(String videoId) {
        return "thumbnails/%s.jpg".formatted(videoId);
    }

    /**
     * The hover preview: a few seconds of animated WebP, stored beside the still thumbnail rather
     * than under the playback prefix because it is not something a player ever loads.
     */
    public static String preview(String videoId) {
        return "previews/%s.webp".formatted(videoId);
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

    /** Written once a transcode's result has been published; outside every public prefix. */
    public static String transcodedMarker(String videoId) {
        return "transcoded/markers/%s".formatted(videoId);
    }

    /**
     * The single marker quarantines wrote before they recorded why — a takedown or a REJECTED
     * verdict. Still honoured, and lifted by a restore. See MediaQuarantineService.
     */
    public static String quarantineMarker(String videoId) {
        return "quarantine/markers/%s".formatted(videoId);
    }

    /**
     * One object per decision holding the video's media off the public path. Not under
     * {@link #quarantineMarker}: MinIO refuses an object below a key that is itself an object.
     */
    public static String quarantineHold(String videoId, String hold) {
        return quarantineHolds(videoId) + hold;
    }

    public static String quarantineHolds(String videoId) {
        return "quarantine/holds/%s/".formatted(videoId);
    }

    public static String hlsPlaylist(String videoId) {
        return hlsPrefix(videoId) + "master.m3u8";
    }
}
