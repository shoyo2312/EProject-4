package com.tiktok.userservice.service;

import com.tiktok.userservice.dto.response.FollowResponse;
import com.tiktok.userservice.dto.response.FriendshipResponse;
import com.tiktok.userservice.dto.response.UserProfileResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface FollowService {

    /** A follow, or a follow request when the account is private — the status says which. */
    FollowResponse follow(Long followerId, Long followingId);

    /** Removes the edge, or withdraws the pending request when there is no edge. */
    void unfollow(Long followerId, Long followingId);

    /** Whether {@code viewerId} and {@code otherUserId} follow each other (mutual = "friends"). */
    FriendshipResponse friendship(Long viewerId, Long otherUserId);

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
