package com.tiktok.storyservice.service;

import com.tiktok.event.video.ModerationVerdict;
import com.tiktok.storyservice.dto.request.CreateStoryRequest;
import com.tiktok.storyservice.dto.request.StoryUploadUrlRequest;
import com.tiktok.storyservice.dto.response.StoryAnnouncementResponse;
import com.tiktok.storyservice.dto.response.StoryGroupResponse;
import com.tiktok.storyservice.dto.response.StoryResponse;
import com.tiktok.storyservice.dto.response.StoryRingResponse;
import com.tiktok.storyservice.dto.response.StoryUploadUrlResponse;
import com.tiktok.storyservice.dto.response.StoryViewerResponse;
import com.tiktok.storyservice.entity.Story;
import com.tiktok.storyservice.entity.StoryMediaType;
import com.tiktok.storyservice.entity.StoryStatus;
import com.tiktok.storyservice.entity.StoryView;
import com.tiktok.storyservice.entity.StoryVisibility;
import com.tiktok.storyservice.exception.ForeignUploadException;
import com.tiktok.storyservice.exception.NotStoryOwnerException;
import com.tiktok.storyservice.exception.StoryNotFoundException;
import com.tiktok.storyservice.exception.TooManyFollowedUsersException;
import com.tiktok.storyservice.exception.TooManyRingUsersException;
import com.tiktok.storyservice.exception.UploadNotFoundException;
import com.tiktok.storyservice.mapper.StoryMapper;
import com.tiktok.storyservice.repository.StoryRepository;
import com.tiktok.storyservice.repository.StoryViewRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Every read a viewer can make goes through the same two filters: the story is ACTIVE (a whitelist,
 * so a status added later is invisible by default) and its visibility is one {@link StoryAudience}
 * allows for that owner. The owner skips both and sees their own stories in every status — the
 * "pending review" and "rejected" labels are how a rejection reaches them.
 */
@Service
@RequiredArgsConstructor
public class StoryServiceImpl implements StoryService {

    /** video-service's MAX_FOLLOWED_USERS: the same follow list feeds both. */
    static final int MAX_FOLLOWED_USERS = 500;

    /** user-service's MAX_BATCH_IDS: the cap its batch endpoints put on ids in one request. */
    static final int MAX_RING_USERS = 100;

    private static final int DEFAULT_VIEWERS_PAGE = 20;
    private static final int MAX_VIEWERS_PAGE = 50;

    private final StoryRepository storyRepository;
    private final StoryViewRepository storyViewRepository;
    private final StoryMapper storyMapper;
    private final StoryMedia storyMedia;
    private final StoryCaptionModeration captionModeration;
    private final StoryAudience storyAudience;

    // ponytail: no per-account limit on how many upload URLs or stories one account may ask for;
    // the signed byte limit bounds each upload. Reuse video-service's UploadRateLimiter if abused.
    @Override
    public StoryUploadUrlResponse createUploadUrl(Long userId, StoryUploadUrlRequest request) {
        return storyMedia.presignUpload(userId, request.contentType());
    }

    @Override
    public StoryResponse create(Long userId, CreateStoryRequest request) {
        String key = storyMedia.keyOf(userId, request.mediaUrl());
        StoryMediaType mediaType = storyMedia.typeOf(key);
        if (!storyMedia.exists(key)) {
            throw new UploadNotFoundException();
        }
        // Before the insert: a refused caption leaves nothing behind, so the upload can be posted again.
        ModerationVerdict textVerdict = captionModeration.screen(request.caption());

        Story story = Story.pending(Story.newId(), userId, key, mediaType, request.caption(),
                request.visibility(), textVerdict, Instant.now());
        try {
            return ownerView(storyRepository.insert(story));
        } catch (DuplicateKeyException e) {
            // The unique media key: one upload backs one story.
            throw new ForeignUploadException();
        }
    }

    @Override
    public StoryResponse getById(Long requesterId, String storyId) {
        Story story = findVisibleTo(requesterId, storyId);
        return isOwner(requesterId, story) ? ownerView(story) : viewerView(story, seenAmong(requesterId, List.of(story)));
    }

    @Override
    public List<StoryResponse> listByUser(Long requesterId, Long userId) {
        Instant now = Instant.now();
        if (userId.equals(requesterId)) {
            return storyRepository.findByUserIdAndDeletedAtIsNullAndExpiresAtAfterOrderByCreatedAtAsc(userId, now)
                    .stream().map(this::ownerView).toList();
        }
        List<Story> visible = visibleOf(requesterId, storyRepository
                .findByUserIdInAndStatusAndDeletedAtIsNullAndExpiresAtAfterOrderByCreatedAtAsc(
                        List.of(userId), StoryStatus.ACTIVE, now));
        Set<String> seen = seenAmong(requesterId, visible);
        return visible.stream().map(story -> viewerView(story, seen)).toList();
    }

    /**
     * The tray: the requester's own stories first, then every followed owner with something the
     * requester may see — those with unseen stories first, then by their latest story.
     *
     * <p>The follow list comes from the client, as on video-service's Following feed. Naming an
     * account one does not follow gains nothing: each owner's stories still pass the same audience
     * check a direct profile read would.
     *
     * <p>ponytail: unpaged. Fine while the followed accounts with live stories number in the
     * hundreds; a cursor over owners is the upgrade.
     */
    @Override
    public List<StoryGroupResponse> feed(Long requesterId, List<Long> followedUserIds) {
        List<Long> followed = followedUserIds == null ? List.of() : followedUserIds;
        if (followed.size() > MAX_FOLLOWED_USERS) {
            throw new TooManyFollowedUsersException(MAX_FOLLOWED_USERS);
        }
        Instant now = Instant.now();
        List<StoryGroupResponse> groups = new ArrayList<>();

        List<Story> own = storyRepository.findByUserIdAndDeletedAtIsNullAndExpiresAtAfterOrderByCreatedAtAsc(requesterId, now);
        if (!own.isEmpty()) {
            groups.add(new StoryGroupResponse(requesterId, own.stream().map(this::ownerView).toList(), false));
        }

        List<Long> others = new LinkedHashSet<>(followed).stream()
                .filter(id -> !id.equals(requesterId)).toList();
        if (others.isEmpty()) {
            return groups;
        }
        List<Story> visible = visibleOf(requesterId, storyRepository
                .findByUserIdInAndStatusAndDeletedAtIsNullAndExpiresAtAfterOrderByCreatedAtAsc(others, StoryStatus.ACTIVE, now));
        Set<String> seen = seenAmong(requesterId, visible);

        Map<Long, List<Story>> byOwner = visible.stream()
                .collect(Collectors.groupingBy(Story::getUserId, LinkedHashMap::new, Collectors.toList()));
        byOwner.entrySet().stream()
                .map(entry -> new StoryGroupResponse(entry.getKey(),
                        entry.getValue().stream().map(story -> viewerView(story, seen)).toList(),
                        entry.getValue().stream().anyMatch(story -> !seen.contains(story.getId()))))
                .sorted(Comparator.comparing(StoryGroupResponse::hasUnseen).reversed()
                        .thenComparing(group -> group.stories().getLast().createdAt(),
                                Comparator.nullsLast(Comparator.reverseOrder())))
                .forEach(groups::add);
        return groups;
    }

    /**
     * Which of these accounts get a ring on their avatar, and whether it is still unseen: the
     * tray's rules (see {@link #feed}) without the media, so no URL is signed. Accounts with
     * nothing to show are absent; the rest come back in the order asked, duplicates ignored.
     *
     * <p>The requester's own ring is red only while a story is ACTIVE, as a story in review is
     * visible to nobody else.
     */
    @Override
    public List<StoryRingResponse> rings(Long requesterId, List<Long> userIds) {
        List<Long> asked = userIds == null ? List.of() : userIds;
        // Against the raw list, before the distinct below, like user-service's followingAmong:
        // what the cap guards is the query string being parsed and bound.
        if (asked.size() > MAX_RING_USERS) {
            throw new TooManyRingUsersException(MAX_RING_USERS);
        }
        List<Long> ids = asked.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        Instant now = Instant.now();
        Map<Long, Boolean> unseenByOwner = new HashMap<>();

        if (requesterId != null && ids.contains(requesterId)) {
            List<Story> own = storyRepository.findByUserIdAndDeletedAtIsNullAndExpiresAtAfterOrderByCreatedAtAsc(requesterId, now);
            if (!own.isEmpty()) {
                unseenByOwner.put(requesterId, own.stream().anyMatch(story -> story.getStatus() == StoryStatus.ACTIVE));
            }
        }

        List<Long> others = ids.stream().filter(id -> !id.equals(requesterId)).toList();
        if (!others.isEmpty()) {
            List<Story> visible = visibleOf(requesterId, storyRepository
                    .findByUserIdInAndStatusAndDeletedAtIsNullAndExpiresAtAfterOrderByCreatedAtAsc(others, StoryStatus.ACTIVE, now));
            Set<String> seen = seenAmong(requesterId, visible);
            visible.forEach(story -> unseenByOwner.merge(story.getUserId(), !seen.contains(story.getId()), Boolean::logicalOr));
        }

        return ids.stream().filter(unseenByOwner::containsKey)
                .map(id -> new StoryRingResponse(id, unseenByOwner.get(id))).toList();
    }

    @Override
    public StoryResponse updateVisibility(Long requesterId, String storyId, StoryVisibility visibility) {
        Story story = findOwn(requesterId, storyId);
        storyRepository.updateVisibility(storyId, visibility);
        StoryResponse current = ownerView(story);
        return new StoryResponse(current.id(), current.userId(), current.mediaUrl(), current.mediaType(),
                current.caption(), visibility, current.status(), current.durationSeconds(), current.viewCount(),
                current.viewedByMe(), current.createdAt(), current.expiresAt());
    }

    @Override
    public void recordView(Long viewerId, String storyId) {
        Story story = findVisibleTo(viewerId, storyId);
        if (isOwner(viewerId, story)) {
            return;
        }

        // The unique (storyId, viewerId) index decides who counted first; checking before inserting
        // let two concurrent views through the check, and one of them then failed on the index.
        try {
            storyViewRepository.insert(StoryView.builder()
                    .storyId(storyId)
                    .viewerId(viewerId)
                    .expiresAt(story.getExpiresAt())
                    .build());
        } catch (DuplicateKeyException e) {
            return;
        }
        storyRepository.incrementViewCount(storyId);
    }

    @Override
    public List<StoryViewerResponse> listViewers(Long requesterId, String storyId, Instant before, Integer size) {
        findOwn(requesterId, storyId);
        int pageSize = size == null ? DEFAULT_VIEWERS_PAGE : Math.clamp(size, 1, MAX_VIEWERS_PAGE);
        return storyViewRepository.findByStoryIdAndViewedAtBeforeOrderByViewedAtDesc(storyId,
                        before == null ? Instant.now() : before, PageRequest.of(0, pageSize))
                .stream().map(storyMapper::toViewerResponse).toList();
    }

    @Override
    public void delete(Long requesterId, String storyId) {
        Story story = storyRepository.findByIdAndDeletedAtIsNull(storyId)
                .orElseThrow(() -> new StoryNotFoundException(storyId));
        if (!isOwner(requesterId, story)) {
            throw new NotStoryOwnerException(storyId);
        }
        storyRepository.markDeleted(storyId, Instant.now());
    }

    /**
     * notification-service's check right before it notifies followers: the event only names the
     * story, which may since have been deleted, expired, or lost its owner to a ban or a closure.
     * Whatever only the owner could see (PENDING_MODERATION, HELD, REJECTED) has nothing to
     * announce either.
     *
     * <p>No audience check: whom to tell follows from the visibility, and is the caller's to decide.
     */
    @Override
    public StoryAnnouncementResponse announcement(String storyId) {
        Story story = storyRepository.findByIdAndDeletedAtIsNull(storyId)
                .filter(found -> !found.isExpired() && found.getStatus() == StoryStatus.ACTIVE)
                .filter(found -> !storyAudience.isHidden(found.getUserId()))
                .orElseThrow(() -> new StoryNotFoundException(storyId));
        return new StoryAnnouncementResponse(story.getUserId(), story.getVisibility());
    }

    /** Not deleted, not expired, and either the requester's own or ACTIVE and in their audience. */
    private Story findVisibleTo(Long requesterId, String storyId) {
        Story story = storyRepository.findByIdAndDeletedAtIsNull(storyId)
                .filter(found -> !found.isExpired())
                .orElseThrow(() -> new StoryNotFoundException(storyId));
        if (isOwner(requesterId, story) || !visibleOf(requesterId, List.of(story)).isEmpty()) {
            return story;
        }
        // 404, not 403: whether a hidden story exists is not something to learn.
        throw new StoryNotFoundException(storyId);
    }

    private Story findOwn(Long requesterId, String storyId) {
        Story story = storyRepository.findByIdAndDeletedAtIsNull(storyId)
                .filter(found -> !found.isExpired())
                .orElseThrow(() -> new StoryNotFoundException(storyId));
        if (!isOwner(requesterId, story)) {
            throw new NotStoryOwnerException(storyId);
        }
        return story;
    }

    /** Someone else's stories, narrowed to ACTIVE ones whose visibility the requester's audience allows. */
    private List<Story> visibleOf(Long requesterId, List<Story> stories) {
        List<Story> active = stories.stream().filter(story -> story.getStatus() == StoryStatus.ACTIVE).toList();
        if (active.isEmpty()) {
            return List.of();
        }
        Map<Long, Set<StoryVisibility>> audience = storyAudience.resolve(requesterId,
                active.stream().map(Story::getUserId).distinct().toList());
        return active.stream()
                .filter(story -> audience.getOrDefault(story.getUserId(), Set.of()).contains(story.getVisibility()))
                .toList();
    }

    private Set<String> seenAmong(Long viewerId, List<Story> stories) {
        if (viewerId == null || stories.isEmpty()) {
            return Set.of();
        }
        return storyViewRepository.findByViewerIdAndStoryIdIn(viewerId, stories.stream().map(Story::getId).toList())
                .stream().map(StoryView::getStoryId).collect(Collectors.toSet());
    }

    private StoryResponse ownerView(Story story) {
        return storyMapper.toResponse(story, storyMedia.signedGet(story.getMediaKey()), false);
    }

    private StoryResponse viewerView(Story story, Set<String> seen) {
        return storyMapper.toResponse(story, storyMedia.signedGet(story.getMediaKey()), seen.contains(story.getId()))
                .forViewer();
    }

    private static boolean isOwner(Long requesterId, Story story) {
        return Objects.equals(requesterId, story.getUserId());
    }
}
