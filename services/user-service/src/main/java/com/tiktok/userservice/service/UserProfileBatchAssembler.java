package com.tiktok.userservice.service;

import com.tiktok.userservice.dto.response.UserProfileResponse;
import com.tiktok.userservice.entity.UserProfile;
import com.tiktok.userservice.exception.UserProfileNotFoundException;
import com.tiktok.userservice.mapper.UserProfileMapper;
import com.tiktok.userservice.repository.UserProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Resolves a page of user ids to profiles with a single batched query, shared by every
 * list endpoint (followers/following/blocked/muted) so none of them fall back to N+1 lookups.
 */
@Component
@RequiredArgsConstructor
public class UserProfileBatchAssembler {

    private final UserProfileRepository userProfileRepository;
    private final UserProfileMapper userProfileMapper;

    public Page<UserProfileResponse> toResponses(Page<Long> userIdsPage) {
        List<Long> userIds = userIdsPage.getContent();
        if (userIds.isEmpty()) {
            return userIdsPage.map(id -> null);
        }

        Map<Long, UserProfile> profilesByUserId = userProfileRepository.findByUserIdInAndDeletedAtIsNull(userIds).stream()
                .collect(Collectors.toMap(UserProfile::getUserId, Function.identity()));

        return userIdsPage.map(userId -> {
            UserProfile profile = profilesByUserId.get(userId);
            if (profile == null) {
                throw new UserProfileNotFoundException(userId);
            }
            return userProfileMapper.toResponse(profile);
        });
    }
}
