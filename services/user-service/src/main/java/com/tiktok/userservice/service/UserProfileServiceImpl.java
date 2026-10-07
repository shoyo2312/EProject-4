package com.tiktok.userservice.service;

import com.tiktok.userservice.dto.request.UpdateProfileRequest;
import com.tiktok.userservice.dto.response.UserProfileResponse;
import com.tiktok.userservice.dto.response.UserStatsCountsResponse;
import com.tiktok.userservice.entity.UserProfile;
import com.tiktok.userservice.exception.DisplayNameChangeTooSoonException;
import com.tiktok.userservice.exception.TooManyProfileIdsException;
import com.tiktok.userservice.exception.UserProfileNotFoundException;
import com.tiktok.userservice.mapper.UserProfileMapper;
import com.tiktok.userservice.repository.UserBlockRepository;
import com.tiktok.userservice.repository.UserProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
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

    /** A trigram is three characters; below that the V8 indexes cannot narrow anything. */
    static final int MIN_ID_SEARCH_LENGTH = 3;

    /** display_name (V1) and username (V8) are both VARCHAR(100). */
    static final int MAX_NAME_LENGTH = 100;

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
        return toResponseFor(viewerId, profile);
    }

    @Override
    @Transactional(readOnly = true)
    public UserProfileResponse getByUsername(Long viewerId, String username) {
        if (username == null || username.isBlank()) {
            throw new UserProfileNotFoundException(username);
        }

        UserProfile profile = userProfileRepository
                .findFirstByUsernameIgnoreCaseAndDeletedAtIsNull(username.strip())
                .orElseThrow(() -> new UserProfileNotFoundException(username));

        // After the lookup, not before: the guard needs the id the handle resolved to.
        profileVisibilityGuard.requireVisible(viewerId, profile.getUserId());
        return toResponseFor(viewerId, profile);
    }

    /** The owner's own profile also says when their name may next change; nobody else's does. */
    private UserProfileResponse toResponseFor(Long viewerId, UserProfile profile) {
        return profile.getUserId().equals(viewerId)
                ? userProfileMapper.toOwnResponse(profile)
                : userProfileMapper.toResponse(profile);
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
    @Transactional(readOnly = true)
    public List<UserStatsCountsResponse> getStatsByUserIds(List<Long> userIds) {
        // Against the raw list, like getByUserIds: the cap guards the query string being parsed
        // and bound, and duplicates pay for that in full.
        if (userIds.size() > MAX_BATCH_IDS) {
            throw new TooManyProfileIdsException(MAX_BATCH_IDS);
        }

        List<Long> wanted = userIds.stream().filter(Objects::nonNull).distinct().toList();
        if (wanted.isEmpty()) {
            return List.of();
        }

        return userProfileRepository.findByUserIdInAndDeletedAtIsNull(wanted).stream()
                .map(p -> new UserStatsCountsResponse(p.getUserId(), p.getFollowerCount(), p.getFollowingCount()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Page<UserProfileResponse> search(Long viewerId, String query, Pageable pageable) {
        if (query == null || query.isBlank()) {
            return Page.empty(pageable);
        }

        // The sort is part of the query — a Pageable carrying its own would be appended to it and
        // fight the follower-count ordering the search is built around.
        Pageable unsorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());

        // Blocks are excluded by the query, so the page comes back full and its total is a number
        // the caller can actually page to. Filtering the page here instead handed out short pages
        // under a total that counted rows this viewer is never shown.
        return userProfileRepository.search(viewerId, escapeLike(query.strip()), unsorted)
                .map(userProfileMapper::toResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Long> searchIds(String query, int limit) {
        if (query == null) {
            return List.of();
        }
        String term = query.strip();
        // The trigram indexes (V8) cannot serve a term under three letters, so it scanned and
        // sorted every profile — once per anonymous video search. Past the name columns' width
        // nothing can contain it.
        if (term.length() < MIN_ID_SEARCH_LENGTH || term.length() > MAX_NAME_LENGTH) {
            return List.of();
        }
        return userProfileRepository.searchIds(escapeLike(term), PageRequest.of(0, limit));
    }

    /** The query is text to find, not a pattern: {@code _} and {@code %} match themselves. */
    private static String escapeLike(String query) {
        return query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    @Override
    @Transactional
    public UserProfileResponse updateOwnProfile(Long userId, UpdateProfileRequest request) {
        UserProfile profile = userProfileRepository.findByUserIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new UserProfileNotFoundException(userId));

        // Only a different name is a change, and whitespace around it is no difference: the current
        // name sent back — by a client posting the whole form, or trimming a name stored with a
        // stray space — neither spends the allowance nor trips over it. A new name is stored
        // stripped.
        String displayName = request.displayName() == null ? null : request.displayName().strip();
        if (displayName != null && !displayName.equals(profile.getDisplayName().strip())) {
            Instant now = Instant.now();
            Instant changeableAt = DisplayNameCooldown.changeableAt(profile.getDisplayNameChangedAt(), now);
            if (changeableAt != null) {
                throw new DisplayNameChangeTooSoonException(changeableAt);
            }
            profile.changeDisplayName(displayName, now);
        }

        profile.updateProfile(request.bio(), request.avatarUrl());

        return userProfileMapper.toOwnResponse(profile);
    }

    @Override
    @Transactional
    public UserProfileResponse replaceOwnAvatarUrl(Long userId, String avatarUrl) {
        UserProfile profile = userProfileRepository.findByUserIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new UserProfileNotFoundException(userId));

        // Long enough to fail the column is a bug in how the URL is built, not a bad upload: the
        // key is a user id and the rest is configuration, so this is a deployment whose endpoint
        // does not fit. Failing loudly beats a silently truncated URL that resolves to nothing.
        if (avatarUrl.length() > MAX_AVATAR_URL_LENGTH) {
            throw new IllegalStateException(
                    "Built an avatar URL of %d characters, over the %d the column holds"
                            .formatted(avatarUrl.length(), MAX_AVATAR_URL_LENGTH));
        }

        profile.updateProfile(null, avatarUrl);
        return userProfileMapper.toOwnResponse(profile);
    }

    @Override
    @Transactional
    public void createFromRegisteredEvent(Long userId, String username, String avatarUrl) {
        if (userProfileRepository.existsByUserIdAndDeletedAtIsNull(userId)) {
            return;
        }

        userProfileRepository.releaseUsername(username, userId);

        UserProfile profile = UserProfile.builder()
                .userId(userId)
                .username(username)
                .displayName(username)
                .avatarUrl(providerAvatar(avatarUrl))
                .build();

        userProfileRepository.save(profile);
    }

    @Override
    @Transactional
    public void applyUsernameChange(Long userId, String username) {
        // Before the read, not after: the release clears the persistence context, so a profile
        // loaded first would be detached and the rename below silently dropped.
        userProfileRepository.releaseUsername(username, userId);

        // A missing profile is a rename that overtook the registration creating it — the two
        // travel on different topics. Thrown, which rolls the release back with it, so the
        // delivery is retried: kafka-lib tries three more times a second apart, enough for the
        // usual gap, then parks the event on auth.username-events.DLT, where the profile keeps the
        // old handle until the event is replayed. Written off here instead, the registration
        // landing afterwards would carry the old handle and nothing would ever correct it.
        UserProfile profile = userProfileRepository.findByUserIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new UserProfileNotFoundException(userId));

        profile.changeUsername(username);
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
