package com.tiktok.userservice.service;

import com.tiktok.userservice.dto.response.UserProfileResponse;
import com.tiktok.userservice.entity.UserProfile;
import com.tiktok.userservice.mapper.UserProfileMapper;
import com.tiktok.userservice.repository.UserProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Resolves a page of user ids to profiles with a single batched query, shared by every
 * list endpoint (followers/following/blocked/muted) so none of them fall back to N+1 lookups.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserProfileBatchAssembler {

    private final UserProfileRepository userProfileRepository;
    private final UserProfileMapper userProfileMapper;

    public Page<UserProfileResponse> toResponses(Page<Long> userIdsPage) {
        List<Long> userIds = userIdsPage.getContent();
        if (userIds.isEmpty()) {
            return Page.empty(userIdsPage.getPageable());
        }

        Map<Long, UserProfile> profilesByUserId = userProfileRepository.findByUserIdInAndDeletedAtIsNull(userIds).stream()
                .collect(Collectors.toMap(UserProfile::getUserId, Function.identity()));

        // An id with no profile is dropped, not fatal. The relationship rows outlive the profile
        // they point at (a profile is soft-deleted, or its UserRegisteredEvent never landed), and
        // throwing here made one such id fail the *entire* page: everybody following that account
        // lost their followers list, with no way to fix it from the API. Dropping degrades to one
        // missing row instead. The page total still counts the edge, so callers keep paging
        // correctly; reconciling the counters is a separate concern.
        List<UserProfileResponse> responses = userIds.stream()
                .map(profilesByUserId::get)
                .filter(Objects::nonNull)
                .map(userProfileMapper::toResponse)
                .toList();

        if (responses.size() < userIds.size()) {
            log.warn("Dropped {} relationship id(s) with no matching profile from a page of {}",
                    userIds.size() - responses.size(), userIds.size());
        }

        return new PageImpl<>(responses, userIdsPage.getPageable(), userIdsPage.getTotalElements());
    }
}
