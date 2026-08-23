package com.tiktok.userservice.service;

import com.tiktok.userservice.dto.request.UpdateProfileRequest;
import com.tiktok.userservice.dto.response.UserProfileResponse;
import com.tiktok.userservice.entity.UserProfile;
import com.tiktok.userservice.exception.TooManyProfileIdsException;
import com.tiktok.userservice.exception.UserProfileNotFoundException;
import com.tiktok.userservice.mapper.UserProfileMapper;
import com.tiktok.userservice.repository.UserBlockRepository;
import com.tiktok.userservice.repository.UserProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserProfileServiceImpl implements UserProfileService {

    /** Matches avatar_url in V1__create_user_profiles_table.sql. */
    private static final int MAX_AVATAR_URL_LENGTH = 500;

    private final UserProfileRepository userProfileRepository;
    private final UserBlockRepository userBlockRepository;
    private final UserProfileMapper userProfileMapper;
    private final ProfileVisibilityGuard profileVisibilityGuard;

    @Override
    @Transactional(readOnly = true)
    public UserProfileResponse getByUserId(Long viewerId, Long userId) {
        profileVisibilityGuard.requireVisible(viewerId, userId);

        UserProfile profile = userProfileRepository.findByUserIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new UserProfileNotFoundException(userId));
        return userProfileMapper.toResponse(profile);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserProfileResponse> getByUserIds(Long viewerId, List<Long> userIds) {
        // Checked against the raw list, before the distinct below: what the cap guards is the query
        // string being parsed and bound, and duplicates pay for that in full.
        if (userIds.size() > MAX_BATCH_IDS) {
            throw new TooManyProfileIdsException(MAX_BATCH_IDS);
        }

        List<Long> wanted = userIds.stream().distinct().toList();
        if (wanted.isEmpty()) {
            return List.of();
        }

        Set<Long> hidden = Set.copyOf(userBlockRepository.findBlockedIdsAmong(viewerId, wanted));
        Map<Long, UserProfile> byUserId = userProfileRepository.findByUserIdInAndDeletedAtIsNull(wanted).stream()
                .collect(Collectors.toMap(UserProfile::getUserId, Function.identity()));

        // Answered in the order asked, so a caller rendering positionally rather than by map
        // lookup is not silently reordered by whatever order Postgres returned the rows in.
        return wanted.stream()
                .filter(id -> !hidden.contains(id))
                .map(byUserId::get)
                .filter(Objects::nonNull)
                .map(userProfileMapper::toResponse)
                .toList();
    }

    @Override
    @Transactional
    public UserProfileResponse updateOwnProfile(Long userId, UpdateProfileRequest request) {
        UserProfile profile = userProfileRepository.findByUserIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new UserProfileNotFoundException(userId));

        profile.updateProfile(request.displayName(), request.bio(), request.avatarUrl());

        return userProfileMapper.toResponse(profile);
    }

    @Override
    @Transactional
    public void createFromRegisteredEvent(Long userId, String username, String avatarUrl) {
        if (userProfileRepository.existsByUserIdAndDeletedAtIsNull(userId)) {
            return;
        }

        UserProfile profile = UserProfile.builder()
                .userId(userId)
                .displayName(username)
                .avatarUrl(providerAvatar(avatarUrl))
                .build();

        userProfileRepository.save(profile);
    }

    @Override
    @Transactional
    public void applyMirroredAvatar(Long userId, String sourceUrl, String avatarUrl) {
        int updated = userProfileRepository.replaceProviderAvatar(userId, sourceUrl, avatarUrl);

        if (updated == 0) {
            // Not an error, and the common case once an account has been around: the user picked
            // their own picture, or the profile does not exist yet because this raced the
            // registration that creates it. The next sign-in announces the same avatar again.
            log.debug("Left the avatar of user {} alone; it is not the provider's to replace", userId);
        }
    }

    /**
     * The identity provider's picture, or null when it is not something we are willing to hand to
     * a browser.
     *
     * <p>Deliberately not {@code @ValidMediaUrl}: that allowlist is our own CDN and buckets, which
     * a Google or Facebook URL is not and never will be. What still has to hold is that the value
     * is an https URL — an event is no less a trust boundary than a request, and a
     * {@code javascript:} string reaching an {@code <img src>} is the whole reason the
     * client-facing field is validated at all — and that it fits avatar_url, VARCHAR(500).
     */
    private static String providerAvatar(String avatarUrl) {
        if (avatarUrl == null
                || !avatarUrl.startsWith("https://")
                || avatarUrl.length() > MAX_AVATAR_URL_LENGTH) {
            return null;
        }
        return avatarUrl;
    }
}
