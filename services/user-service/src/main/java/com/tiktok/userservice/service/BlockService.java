package com.tiktok.userservice.service;

import com.tiktok.userservice.dto.response.BlockResponse;
import com.tiktok.userservice.dto.response.UserProfileResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface BlockService {

    BlockResponse block(Long blockerId, Long blockedId);

    void unblock(Long blockerId, Long blockedId);

    Page<UserProfileResponse> listBlocked(Long userId, Pageable pageable);
}
