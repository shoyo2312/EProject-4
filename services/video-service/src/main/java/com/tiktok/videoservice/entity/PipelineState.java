package com.tiktok.videoservice.entity;

/**
 * What a status write read, and has to find unchanged for it to land.
 *
 * <p>Status alone is not enough. On a taken-down video {@code status} stays TAKEN_DOWN while the
 * pipeline moves on in {@code statusBeforeTakedown}, and a verdict that beats its transcode result
 * is recorded without moving either (see {@link Video#applyModeration}). A write conditioned on
 * status alone lands over both, from a copy that no longer describes the video.
 *
 * @param moderated whether a moderation verdict is on file
 */
public record PipelineState(VideoStatus status, VideoStatus statusBeforeTakedown, boolean moderated) {

    public static PipelineState of(Video video) {
        return new PipelineState(video.getStatus(), video.getStatusBeforeTakedown(), video.getModeration() != null);
    }
}
