package com.tiktok.videoservice.client;

/**
 * One track from the music catalogue, as this service uses it.
 *
 * @param audioUrl    a stream to preview in the app; carries a short-lived token, so never stored
 * @param downloadUrl the stable file media-worker mixes from
 * @param licenseUrl  the Creative Commons licence the artist published it under — attribution is a
 *                    condition of every one of them, so it is shown with every video using it
 * @param shareUrl    the track's page, the attribution's link
 */
public record MusicTrack(
        String id,
        String title,
        String artist,
        int durationSeconds,
        String audioUrl,
        String downloadUrl,
        String imageUrl,
        String licenseUrl,
        String shareUrl
) {
}
