package com.tiktok.videoservice.dto.response;

import com.tiktok.videoservice.client.MusicTrack;

/**
 * A track the editor can put under a video.
 *
 * @param audioUrl   a stream for previewing in the app. Short-lived: play it now, never store it
 * @param licenseUrl the Creative Commons licence, shown with the credit
 */
public record MusicTrackResponse(
        String id,
        String title,
        String artist,
        int durationSeconds,
        String audioUrl,
        String imageUrl,
        String licenseUrl,
        String shareUrl
) {

    public static MusicTrackResponse of(MusicTrack track) {
        return new MusicTrackResponse(track.id(), track.title(), track.artist(), track.durationSeconds(),
                track.audioUrl(), track.imageUrl(), track.licenseUrl(), track.shareUrl());
    }
}
