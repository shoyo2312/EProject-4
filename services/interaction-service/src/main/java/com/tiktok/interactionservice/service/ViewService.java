package com.tiktok.interactionservice.service;

import com.tiktok.interactionservice.dto.request.ViewRequest;
import com.tiktok.interactionservice.dto.request.WatchRequest;
import com.tiktok.interactionservice.dto.response.ViewResponse;
import com.tiktok.interactionservice.dto.response.WatchResponse;

public interface ViewService {

    /**
     * Records one playback of the video. Counts every play, replays included — the same viewer
     * watching three times moves the counter three times, which is what a view count is expected
     * to mean. Safe to call more than once for the same playback: only the first call carrying a
     * given playId moves the counter or emits an event.
     */
    ViewResponse recordView(Long videoId, Long currentUserId, ViewRequest request);

    /**
     * Records one finished playback session as a training label for recommendation.
     *
     * <p>Writes nothing. This is a stream, not state: it moves no counter and is displayed nowhere.
     */
    WatchResponse recordWatch(Long videoId, Long currentUserId, WatchRequest request);
}
