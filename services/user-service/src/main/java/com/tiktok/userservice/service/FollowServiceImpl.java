package com.tiktok.userservice.service;

import com.tiktok.userservice.dto.response.FollowResponse;
import com.tiktok.userservice.dto.response.FriendshipResponse;
import com.tiktok.userservice.dto.response.UserProfileResponse;
import com.tiktok.userservice.entity.FollowRequest;
import com.tiktok.userservice.entity.UserFollow;
import com.tiktok.userservice.entity.UserProfile;
import com.tiktok.userservice.event.producer.UserEventPublisher;
import com.tiktok.userservice.exception.AlreadyFollowingException;
import com.tiktok.userservice.exception.AlreadyRequestedException;
import com.tiktok.userservice.exception.CannotFollowBlockedUserException;
import com.tiktok.userservice.exception.CannotFollowSelfException;
import com.tiktok.userservice.exception.FollowRequestNotFoundException;
import com.tiktok.userservice.exception.NotFollowingException;
import com.tiktok.userservice.exception.PrivateAccountException;
import com.tiktok.userservice.exception.TooManyProfileIdsException;
import com.tiktok.userservice.exception.UserProfileNotFoundException;
import com.tiktok.userservice.repository.FollowRequestRepository;
import com.tiktok.userservice.repository.UserBlockRepository;
import com.tiktok.userservice.repository.UserFollowRepository;
import com.tiktok.userservice.repository.UserProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class FollowServiceImpl implements FollowService {

    private final UserFollowRepository userFollowRepository;
    private final UserProfileRepository userProfileRepository;
    private final UserBlockRepository userBlockRepository;
    private final FollowRequestRepository followRequestRepository;
    private final UserProfileBatchAssembler profileBatchAssembler;
    private final ProfileVisibilityGuard profileVisibilityGuard;
    private final UserEventPublisher userEventPublisher;
    private final UserPairLock pairLock;

    @Override
    @Transactional
    public FollowResponse follow(Long followerId, Long followingId) {
        pairLock.acquire(followerId, followingId);
        if (followerId.equals(followingId)) {
            throw new CannotFollowSelfException();
        }

        // No FK backs user_follows.following_id, and the counter updates are UPDATE ... WHERE
        // that silently match zero rows — without this an edge to a non-existent user is
        // accepted with a 200 and leaves followingCount permanently overstated.
        UserProfile target = userProfileRepository.findByUserIdAndDeletedAtIsNull(followingId)
                .filter(found -> !found.isDeactivated())
                .orElseThrow(() -> new UserProfileNotFoundException(followingId));

        // The follower is checked for the same reason as the target, on the other side of the
        // edge. A caller whose UserRegisteredEvent never landed holds a valid token but no
        // profile row; incrementFollowingCount would match nothing and the edge would put an id
        // with no profile into the target's followers list forever.
        if (!userProfileRepository.existsByUserIdAndDeletedAtIsNull(followerId)) {
            throw new UserProfileNotFoundException(followerId);
        }

        if (userBlockRepository.existsBlockBetween(followerId, followingId)) {
            throw new CannotFollowBlockedUserException();
        }

        // Before the private branch: going private keeps existing followers, so an edge that is
        // already there answers the same way on either kind of account.
        if (hasEdge(followerId, followingId)) {
            throw new AlreadyFollowingException();
        }

        return target.isPrivateAccount()
                ? request(followerId, followingId)
                : addEdge(followerId, followingId, false);
    }

    @Override
    @Transactional
    public void unfollow(Long followerId, Long followingId) {
        pairLock.acquire(followerId, followingId);
        Optional<UserFollow> follow =
                userFollowRepository.findByFollowerIdAndFollowingIdAndDeletedAtIsNull(followerId, followingId);
        if (follow.isPresent()) {
            follow.get().markDeleted();
            userProfileRepository.decrementFollowingCount(followerId);
            userProfileRepository.decrementFollowerCount(followingId);
            userEventPublisher.publishFollowChanged(followerId, followingId, false);
            return;
        }

        // No edge: on a private account the button reads "Requested", and pressing it again
        // withdraws the request. Nothing is announced — no follow ever existed.
        followRequestRepository.findByRequesterIdAndTargetIdAndDeletedAtIsNull(followerId, followingId)
                .orElseThrow(NotFollowingException::new)
                .markDeleted();
    }

    @Override
    @Transactional(readOnly = true)
    public FriendshipResponse friendship(Long viewerId, Long otherUserId) {
        // Read-only and edge-only: a non-existent user has no follow rows and no profile, so it
        // answers all false rather than 404, and self is never a friend.
        boolean privateAccount = userProfileRepository.existsByUserIdAndDeletedAtIsNullAndPrivateAccountTrue(otherUserId);
        if (viewerId == null || viewerId.equals(otherUserId)) {
            return new FriendshipResponse(otherUserId, false, false, false, privateAccount);
        }

        boolean following = hasEdge(viewerId, otherUserId);
        boolean friends = following && hasEdge(otherUserId, viewerId);
        boolean requested = !following && followRequestRepository
                .findByRequesterIdAndTargetIdAndDeletedAtIsNull(viewerId, otherUserId).isPresent();
        return new FriendshipResponse(otherUserId, friends, following, requested, privateAccount);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Long> followingAmong(Long followerId, List<Long> userIds) {
        // Against the raw list, before the distinct below, like UserProfileService.getByUserIds:
        // what the cap guards is the query string being parsed and bound.
        if (userIds.size() > UserProfileService.MAX_BATCH_IDS) {
            throw new TooManyProfileIdsException(UserProfileService.MAX_BATCH_IDS);
        }

        List<Long> distinctIds = userIds.stream().filter(Objects::nonNull).distinct().toList();
        return distinctIds.isEmpty()
                ? List.of()
                : userFollowRepository.findFollowingIdsAmong(followerId, distinctIds);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<UserProfileResponse> listFollowers(Long viewerId, Long userId, Pageable pageable) {
        requireListVisible(viewerId, userId);

        // Visible-to rather than the plain lookup: the guard above answers whether this list may be
        // read, the query answers who in it may be shown. Someone who blocked the viewer is neither.
        return profileBatchAssembler.toResponses(
                userFollowRepository.findFollowerIdsVisibleTo(viewerId, userId, pageable));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<UserProfileResponse> listFollowing(Long viewerId, Long userId, Pageable pageable) {
        requireListVisible(viewerId, userId);

        return profileBatchAssembler.toResponses(
                userFollowRepository.findFollowingIdsVisibleTo(viewerId, userId, pageable));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<UserProfileResponse> listFollowRequests(Long ownerId, Pageable pageable) {
        // No block filter: blocking ends the requests between the two accounts.
        return profileBatchAssembler.toResponses(followRequestRepository
                .findByTargetIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(ownerId, pageable)
                .map(FollowRequest::getRequesterId));
    }

    @Override
    @Transactional
    public FollowResponse approveFollowRequest(Long ownerId, Long requesterId) {
        pairLock.acquire(ownerId, requesterId);
        FollowRequest request = followRequestRepository
                .findByRequesterIdAndTargetIdAndDeletedAtIsNull(requesterId, ownerId)
                .orElseThrow(FollowRequestNotFoundException::new);
        if (userBlockRepository.existsBlockBetween(ownerId, requesterId)) {
            throw new CannotFollowBlockedUserException();
        }
        approve(List.of(request));
        return new FollowResponse(requesterId, ownerId, FollowResponse.Status.FOLLOWING);
    }

    @Override
    @Transactional
    public void rejectFollowRequest(Long ownerId, Long requesterId) {
        followRequestRepository.findByRequesterIdAndTargetIdAndDeletedAtIsNull(requesterId, ownerId)
                .orElseThrow(FollowRequestNotFoundException::new)
                .markDeleted();
    }

    /**
     * ponytail: one transaction for all of them. Fine at this scale; an account with thousands of
     * pending requests would want the approvals batched by a job instead.
     */
    @Override
    @Transactional
    public void approveAllFollowRequests(Long ownerId) {
        // Lock in a deterministic order, then re-read: block may have ended a request while we
        // waited. Do not reuse the list read before acquiring the locks.
        List<Long> requesters = followRequestRepository.findByTargetIdAndDeletedAtIsNull(ownerId).stream()
                .map(FollowRequest::getRequesterId).distinct().sorted().toList();
        requesters.forEach(requester -> pairLock.acquire(ownerId, requester));
        approve(followRequestRepository.findByTargetIdAndDeletedAtIsNull(ownerId).stream()
                .filter(request -> requesters.contains(request.getRequesterId())).toList());
    }

    /**
     * A follow of a private account: a request its owner answers, not an edge. Same race and same
     * guarantee as the edge in {@link #addEdge} — the check is the fast path,
     * uq_user_follow_requests_requester_target the guarantee.
     *
     * <p>ponytail: an owner going public between the profile read in {@link #follow} and this
     * insert leaves one pending request on a public account, because approveAllFollowRequests ran
     * before it existed. It stays answerable from the requests list; closing the gap needs a lock
     * on the profile row.
     */
    private FollowResponse request(Long requesterId, Long targetId) {
        if (followRequestRepository.findByRequesterIdAndTargetIdAndDeletedAtIsNull(requesterId, targetId).isPresent()) {
            throw new AlreadyRequestedException();
        }

        try {
            followRequestRepository.saveAndFlush(FollowRequest.builder()
                    .requesterId(requesterId)
                    .targetId(targetId)
                    .build());
        } catch (DataIntegrityViolationException ex) {
            throw new AlreadyRequestedException();
        }

        userEventPublisher.publishFollowRequested(requesterId, targetId);
        return new FollowResponse(requesterId, targetId, FollowResponse.Status.REQUESTED);
    }

    /** The edge, both counters and the announcement — one path for a follow and an approval. */
    private FollowResponse addEdge(Long followerId, Long followingId, boolean requestApproved) {
        // The check in follow() is not the guarantee — two concurrent requests both pass it and
        // only uq_user_follows_follower_following stops the second. saveAndFlush forces that
        // violation to surface here instead of at commit, so the loser is answered 409
        // ALREADY_FOLLOWING like the sequential case rather than a 500, and rolls back before the
        // counters move.
        try {
            userFollowRepository.saveAndFlush(UserFollow.builder()
                    .followerId(followerId)
                    .followingId(followingId)
                    .build());
        } catch (DataIntegrityViolationException ex) {
            throw new AlreadyFollowingException();
        }

        userProfileRepository.incrementFollowingCount(followerId);
        userProfileRepository.incrementFollowerCount(followingId);
        userEventPublisher.publishFollowChanged(followerId, followingId, true, requestApproved);

        return new FollowResponse(followerId, followingId, FollowResponse.Status.FOLLOWING);
    }

    /**
     * Every request is ended before any edge is written. Each edge moves the counters through bulk
     * updates that clear the persistence context, and a request still waiting to be ended by then
     * would be detached, its end silently lost.
     */
    private void approve(List<FollowRequest> requests) {
        requests.forEach(FollowRequest::markDeleted);
        for (FollowRequest request : requests) {
            if (userBlockRepository.existsBlockBetween(request.getRequesterId(), request.getTargetId())) {
                continue;
            }
            // The edge can already be there: a request left pending across a switch to public (see
            // request()), then a direct follow. Ending the request is all that is left to do.
            if (!hasEdge(request.getRequesterId(), request.getTargetId())) {
                addEdge(request.getRequesterId(), request.getTargetId(), true);
            }
        }
    }

    /**
     * The block guard first — still a 404 — then the private-account rule: a private account's
     * lists are for its followers.
     */
    private void requireListVisible(Long viewerId, Long userId) {
        profileVisibilityGuard.requireVisible(viewerId, userId);
        if (userProfileRepository.existsByUserIdAndDeactivatedAtIsNotNull(userId)) {
            throw new UserProfileNotFoundException(userId);
        }
        if (!viewerId.equals(userId)
                && userProfileRepository.existsByUserIdAndDeletedAtIsNullAndPrivateAccountTrue(userId)
                && !hasEdge(viewerId, userId)) {
            throw new PrivateAccountException();
        }
    }

    private boolean hasEdge(Long followerId, Long followingId) {
        return userFollowRepository.findByFollowerIdAndFollowingIdAndDeletedAtIsNull(followerId, followingId)
                .isPresent();
    }
}
