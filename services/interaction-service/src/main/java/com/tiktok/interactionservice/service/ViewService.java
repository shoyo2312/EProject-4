package com.tiktok.interactionservice.service;

import com.tiktok.interactionservice.dto.request.WatchRequest;
import com.tiktok.interactionservice.dto.response.ViewResponse;
import com.tiktok.interactionservice.dto.response.WatchResponse;

public interface ViewService {

    /**
     * Records that the current user watched the video. Safe to call more than once: only the
     * first call inside the dedup window moves the counter or emits an event.
     */
    ViewResponse recordView(Long videoId, Long currentUserId);

    /**
     * Records one finished playback session as a training label for recommendation.
     *
     * <p>Deliberately not deduplicated, unlike {@link #recordView}: the counter needs one view
     * per viewer per day to mean anything, while a ranking model needs every session — a viewer
     * who watches the same video three times is the strongest positive signal there is, and
     * deduplicating that away would leave the model trained on first impressions only.
     *
     * <p>Writes nothing. This is a stream, not state: it moves no counter and is displayed nowhere.
     */
    WatchResponse recordWatch(Long videoId, Long currentUserId, WatchRequest request);
}
