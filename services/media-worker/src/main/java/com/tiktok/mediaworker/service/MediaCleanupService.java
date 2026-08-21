package com.tiktok.mediaworker.service;

public interface MediaCleanupService {

    /**
     * Removes everything one video occupies in storage: the source upload, the thumbnail, and
     * every object under its HLS prefix.
     */
    void deleteMediaFor(String videoId, String rawFileUrl);
}
