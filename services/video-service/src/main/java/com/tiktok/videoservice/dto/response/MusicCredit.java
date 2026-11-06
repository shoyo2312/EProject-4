package com.tiktok.videoservice.dto.response;

import com.tiktok.videoservice.entity.VideoEdit;

/**
 * The music under a video, credited as its Creative Commons licence requires: title, artist, a
 * link to the track and the licence. Null on a video without music.
 */
public record MusicCredit(String trackId, String title, String artist, String licenseUrl, String shareUrl) {

    public static MusicCredit of(VideoEdit edit) {
        if (edit == null || edit.getMusic() == null) {
            return null;
        }
        VideoEdit.Music music = edit.getMusic();
        return new MusicCredit(music.getTrackId(), music.getTitle(), music.getArtist(),
                music.getLicenseUrl(), music.getShareUrl());
    }
}
