package com.tiktok.videoservice.service;

import com.tiktok.videoservice.client.JamendoClient;
import com.tiktok.videoservice.client.MusicTrack;
import com.tiktok.videoservice.exception.MusicNotFoundException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MusicServiceImplTest {

    private final JamendoClient jamendo = mock(JamendoClient.class);
    private final MusicService music = new MusicServiceImpl(jamendo);

    private static MusicTrack track(String id) {
        return new MusicTrack(id, "Song " + id, "Artist", 90, "https://stream/" + id,
                "https://prod-1.storage.jamendo.com/download/track/" + id + "/mp32/", "https://img/" + id,
                "http://creativecommons.org/licenses/by/3.0/", "https://www.jamendo.com/track/" + id);
    }

    /** Everyone opening the picker asks for the same first page; the catalogue hears it once. */
    @Test
    void aPageIsFetchedOncePerTtl_whatEverItsCase() {
        when(jamendo.search("chill", 20, 20)).thenReturn(List.of(track("1")));

        music.search("Chill", 1, 20);
        music.search(" chill ", 1, 20);

        verify(jamendo, times(1)).search("chill", 20, 20);
    }

    /** Remembering a moment's empty answer would show everyone an empty picker for ten minutes. */
    @Test
    void anEmptyPage_isNotRemembered() {
        when(jamendo.search(anyString(), anyInt(), anyInt())).thenReturn(List.of());

        music.search("", 0, 20);
        music.search("", 0, 20);

        verify(jamendo, times(2)).search("", 0, 20);
    }

    @Test
    void aTrackSeenInAPage_isPublishedWithoutAnotherCall() {
        when(jamendo.search("", 0, 20)).thenReturn(List.of(track("5")));
        music.search(null, 0, 20);

        assertThat(music.requireTrack("5").downloadUrl()).contains("/track/5/");
        verify(jamendo, never()).find(anyString());
    }

    @Test
    void aTrackTheCatalogueWillNotOffer_isNotFound() {
        when(jamendo.find("9")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> music.requireTrack("9")).isInstanceOf(MusicNotFoundException.class);
    }
}
