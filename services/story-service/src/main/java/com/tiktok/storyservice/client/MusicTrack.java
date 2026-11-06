package com.tiktok.storyservice.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A catalogue track as video-service's internal lookup describes it.
 *
 * @param downloadUrl the stable file media-worker mixes from
 * @param licenseUrl  the Creative Commons licence — attribution is a condition of every one
 * @param shareUrl    the track's page, the attribution's link
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MusicTrack(
        String id,
        String title,
        String artist,
        String downloadUrl,
        String licenseUrl,
        String shareUrl
) {
}
