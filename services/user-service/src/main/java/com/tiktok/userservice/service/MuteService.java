package com.tiktok.userservice.service;

import com.tiktok.userservice.dto.response.MuteResponse;
import com.tiktok.userservice.dto.response.UserProfileResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface MuteService {

    MuteResponse mute(Long muterId, Long mutedId);

    void unmute(Long muterId, Long mutedId);

    Page<UserProfileResponse> listMuted(Long userId, Pageable pageable);

    /** Every account this user currently mutes, for the feed to filter on. */
    List<Long> mutedIds(Long userId);
}
