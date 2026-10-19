package com.tiktok.interactionservice.service;

import com.tiktok.interactionservice.dto.response.RepostContextResponse;
import com.tiktok.interactionservice.dto.response.RepostStatusResponse;
import com.tiktok.interactionservice.dto.response.VideoIdPageResponse;

import java.util.List;

public interface RepostService {

    RepostStatusResponse repost(Long videoId, Long currentUserId);

    RepostStatusResponse unrepost(Long videoId, Long currentUserId);

    List<RepostContextResponse> getContexts(List<Long> videoIds, Long currentUserId);

    /**
     * The reposts on {@code userId}'s profile. A private account's are for its owner and followers:
     * anyone else — a null {@code viewerId}, the anonymous caller, included — gets PRIVATE_ACCOUNT.
     */
    VideoIdPageResponse listReposts(Long viewerId, Long userId, String cursor, int size);
}
