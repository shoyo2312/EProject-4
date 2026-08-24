package com.tiktok.userservice.service;

import com.tiktok.userservice.dto.request.UpdateProfileRequest;
import com.tiktok.userservice.dto.response.UserProfileResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface UserProfileService {

    /** Cap on {@link #getByUserIds}, mirrored by the caller's own page size. */
    int MAX_BATCH_IDS = 100;

    UserProfileResponse getByUserId(Long viewerId, Long userId);

    /**
     * The same lookup for a page of ids in two queries flat, for callers rendering a list that
     * carries user ids and nothing else — a video feed, a comment thread.
     *
     * <p>Ids the viewer cannot see are dropped, not raised: an id with no profile (the
     * relationship outlived it, or its UserRegisteredEvent never landed) and an id on either side
     * of a block are both simply absent from the answer. The block call is the same one
     * {@link #getByUserId} makes — it answers 404 rather than 403, so a blocked id is
     * indistinguishable from a missing one either way — but failing the whole page because one
     * author blocked the viewer would cost every other row on it. Callers key the result by
     * {@code userId}, so a short list reads fine; a 404'd page does not.
     *
     * <p>Duplicate ids collapse. More than {@code MAX_BATCH_IDS} ids is a
     * {@link com.tiktok.userservice.exception.TooManyProfileIdsException}.
     */
    List<UserProfileResponse> getByUserIds(Long viewerId, List<Long> userIds);

    /**
     * Profile search by handle or display name, newest-irrelevant and ordered by follower count.
     *
     * <p>A blank query is an empty page, not the whole table: there is no useful answer to "show
     * me everyone", and paging through every profile is the one request that would make this
     * endpoint expensive.
     *
     * <p>Blocked profiles are dropped from the page the same way {@link #getByUserIds} drops
     * them, which can leave a page shorter than the size asked for. Filtering before paging would
     * mean the block check joining the search query, and a block is the rarer thing by orders of
     * magnitude.
     */
    Page<UserProfileResponse> search(Long viewerId, String query, Pageable pageable);

    UserProfileResponse updateOwnProfile(Long userId, UpdateProfileRequest request);

    /**
     * Creates the profile a newly registered account gets by default.
     *
     * <p>Takes no email on purpose: nothing in a profile is addressed by email, and a copy kept
     * here would be a second place for it to be wrong once the account changes it. auth-service
     * owns that field.
     *
     * <p>{@code avatarUrl} is the picture the identity provider already held for a social signup,
     * null for a password one. It only ever seeds the profile — any avatar after that is the
     * user's own, set through {@link #updateOwnProfile} and never overwritten from here.
     */
    void createFromRegisteredEvent(Long userId, String username, String avatarUrl);

    /**
     * Moves a profile off the provider's URL and onto our own copy of the same picture.
     *
     * <p>Also how an account that predates provider avatars gets one at all: a profile with no
     * avatar takes the copy too. A profile whose owner has since chosen a picture keeps it.
     */
    void applyMirroredAvatar(Long userId, String sourceUrl, String avatarUrl);
}
