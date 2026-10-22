package com.tiktok.userservice.service;

import com.tiktok.userservice.dto.request.StoryAudienceScope;
import com.tiktok.userservice.dto.response.FollowResponse;
import com.tiktok.userservice.dto.response.FriendshipResponse;
import com.tiktok.userservice.dto.response.StoryAudienceResponse;
import com.tiktok.userservice.dto.response.UserProfileResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface FollowService {

    /** A follow, or a follow request when the account is private — the status says which. */
    FollowResponse follow(Long followerId, Long followingId);

    /** Removes the edge, or withdraws the pending request when there is no edge. */
    void unfollow(Long followerId, Long followingId);

    /** The viewer's relationship to {@code otherUserId}; {@code viewerId} is null for an anonymous viewer. */
    FriendshipResponse friendship(Long viewerId, Long otherUserId);

    /** The subset of {@code userIds} that {@code followerId} follows. At most 100 ids. */
    List<Long> followingAmong(Long followerId, List<Long> userIds);

    /**
     * One page of who a new story of {@code ownerId} is announced to, for notification-service: the
     * owner's followers, or the followers the owner follows back. Keyset paging, so follows changing
     * while a fan-out walks the pages cannot shift them: {@code after} is the last id of the page
     * before (null for the first) and {@code size} defaults to, and is capped at, 500.
     */
    StoryAudienceResponse storyAudience(Long ownerId, StoryAudienceScope scope, Long after, Integer size);

    Page<UserProfileResponse> listFollowers(Long viewerId, Long userId, Pageable pageable);

    Page<UserProfileResponse> listFollowing(Long viewerId, Long userId, Pageable pageable);

    /** The owner's pending requests, newest first, as the requesters' profiles. */
    Page<UserProfileResponse> listFollowRequests(Long ownerId, Pageable pageable);

    FollowResponse approveFollowRequest(Long ownerId, Long requesterId);

    /** Silent: the requester is not told. */
    void rejectFollowRequest(Long ownerId, Long requesterId);

    /** Every pending request of an account going public, in the caller's transaction. */
    void approveAllFollowRequests(Long ownerId);
}
