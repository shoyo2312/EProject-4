package com.tiktok.mediaworker.service;

/**
 * What ffmpeg can read off an upload before anything is done to it. The codec and pixel
 * dimensions are here for one decision only — see {@link #needsNormalizing()}.
 *
 * @param audioCodec null when the file has no audio track at all, which is a normal thing for an
 *                   upload to be and not a reason to re-encode it
 */
public record ProbedVideo(
        int durationSeconds,
        String videoCodec,
        String audioCodec,
        int width,
        int height
) {

    /** 720p, in the orientation the video is actually in: 1280x720 landscape, 720x1280 portrait. */
    private static final int MAX_LONG_SIDE = 1280;
    private static final int MAX_SHORT_SIDE = 720;

    /**
     * Whether this upload has to be re-encoded before a browser can play it at a sane size.
     *
     * <p>H.264 video in an AAC (or silent) track at 720p or below is what every browser plays and
     * what the rest of the pipeline assumes, so a file already in that shape is left alone and
     * only remuxed. Everything else — HEVC from an iPhone, AV1, VP8/VP9 from a browser recorder,
     * a 4K phone capture — is normalized.
     *
     * <p>Rotation does not enter into it. A rotated file is stored in its pre-rotation dimensions,
     * so a portrait 1080x1920 capture reads as 1920x1080 here; either way both sides are compared,
     * so a file too big to serve is caught in whichever orientation it is stored in.
     */
    public boolean needsNormalizing() {
        return !"h264".equals(videoCodec)
                || !(audioCodec == null || "aac".equals(audioCodec))
                || Math.max(width, height) > MAX_LONG_SIDE
                || Math.min(width, height) > MAX_SHORT_SIDE;
    }
}
