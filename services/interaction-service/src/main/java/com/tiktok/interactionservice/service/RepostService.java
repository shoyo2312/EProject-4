package com.tiktok.interactionservice.service;

import com.tiktok.interactionservice.dto.response.RepostContextResponse;
import com.tiktok.interactionservice.dto.response.RepostStatusResponse;
import com.tiktok.interactionservice.dto.response.VideoIdPageResponse;

import java.util.List;

public interface RepostService {

    RepostStatusResponse repost(Long videoId, Long currentUserId);

    RepostStatusResponse unrepost(Long videoId, Long currentUserId);

    List<RepostContextResponse> getContexts(List<Long> videoIds, Long currentUserId);

    VideoIdPageResponse listReposts(Long currentUserId, String cursor, int size);
}
