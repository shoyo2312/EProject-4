package com.tiktok.interactionservice.service;

import com.tiktok.interactionservice.dto.response.ViewResponse;

public interface ViewService {

    /**
     * Records that the current user watched the video. Safe to call more than once: only the
     * first call inside the dedup window moves the counter or emits an event.
     */
    ViewResponse recordView(Long videoId, Long currentUserId);
}
