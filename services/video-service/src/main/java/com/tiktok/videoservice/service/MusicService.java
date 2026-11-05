package com.tiktok.videoservice.service;

import com.tiktok.videoservice.client.MusicTrack;
import com.tiktok.videoservice.dto.response.MusicTrackResponse;

import java.util.List;

/** The music catalogue the editor picks from, and the lookup publish checks a pick against. */
public interface MusicService {

    /**
     * @param query blank for the most popular tracks
     * @throws com.tiktok.videoservice.exception.MusicUnavailableException when the catalogue cannot be read
     */
    List<MusicTrackResponse> search(String query, int page, int size);

    /**
     * @throws com.tiktok.videoservice.exception.MusicNotFoundException    for a track that cannot be used
     * @throws com.tiktok.videoservice.exception.MusicUnavailableException when the catalogue cannot be read
     */
    MusicTrack requireTrack(String trackId);
}
