package com.tiktok.interactionservice.service;

import com.tiktok.interactionservice.dto.response.ShareResponse;

public interface ShareService {

    ShareResponse share(Long videoId, Long currentUserId);
}
