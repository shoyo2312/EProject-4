package com.tiktok.storyservice.dto.response;

import com.tiktok.storyservice.entity.StoryEdit;

/**
 * The song under a story, credited: every Creative Commons licence asks for the title, the artist
 * and a link wherever the work is used, so the viewer shows them with the story.
 */
public record StoryMusicCredit(
        String trackId,
        String title,
        String artist,
        String licenseUrl,
        String shareUrl
) {

    /** Null for a story without a song. */
    public static StoryMusicCredit of(StoryEdit edit) {
        if (edit == null || edit.getMusic() == null) {
            return null;
        }
        StoryEdit.Music music = edit.getMusic();
        return new StoryMusicCredit(music.getTrackId(), music.getTitle(), music.getArtist(),
                music.getLicenseUrl(), music.getShareUrl());
    }
}
