package com.tiktok.userservice.service;

import com.tiktok.userservice.dto.response.MuteResponse;
import com.tiktok.userservice.dto.response.UserProfileResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface MuteService {

    MuteResponse mute(Long muterId, Long mutedId);

    void unmute(Long muterId, Long mutedId);

    Page<UserProfileResponse> listMuted(Long userId, Pageable pageable);
}
