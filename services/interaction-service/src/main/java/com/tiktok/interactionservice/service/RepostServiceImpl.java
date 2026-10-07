package com.tiktok.interactionservice.service;

import com.tiktok.interactionservice.client.FriendshipClient;
import com.tiktok.interactionservice.client.VideoOwnershipClient;
import com.tiktok.interactionservice.dto.response.RepostContextResponse;
import com.tiktok.interactionservice.dto.response.RepostStatusResponse;
import com.tiktok.interactionservice.dto.response.VideoIdPageResponse;
import com.tiktok.interactionservice.entity.RepostByUser;
import com.tiktok.interactionservice.entity.RepostByUserKey;
import com.tiktok.interactionservice.entity.RepostByVideo;
import com.tiktok.interactionservice.entity.RepostByVideoKey;
import com.tiktok.interactionservice.event.producer.InteractionEventPublisher;
import com.tiktok.interactionservice.exception.InvalidCursorException;
import com.tiktok.interactionservice.exception.PrivateAccountException;
import com.tiktok.interactionservice.exception.RepostRateLimitedException;
import com.tiktok.interactionservice.repository.RepostByUserRepository;
import com.tiktok.interactionservice.repository.RepostByVideoRepository;
import com.tiktok.interactionservice.repository.VideoCountersRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.cassandra.CassandraInvalidQueryException;
import org.springframework.data.cassandra.core.query.CassandraPageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Repost is a toggle, exactly like {@link LikeServiceImpl} — not an event log like
 * {@link ShareServiceImpl}: a "remove repost" action needs one row per (user, video) to find and
 * delete, so repost/un-repost mirrors like/unlike's claim-row-plus-reverse-index shape rather than
 * share's per-action row. See that class for why each step is ordered the way it is.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RepostServiceImpl implements RepostService {

    /** Same ceiling the paged listings and like-status batch use — one feed page's worth. */
    private static final int MAX_BATCH_SIZE = 50;

    /**
     * ponytail: capped, unordered reposter scan (see {@code RepostByVideoRepository}) rather than
     * a proper "most recent reposters" index. Good enough to find one followed account among a
     * video's reposters; add a time-ordered reverse index if the badge ever needs to prefer the
     * most recent one specifically.
     */
    private static final int RECENT_REPOSTERS_LIMIT = 20;

    private final RepostByVideoRepository repostByVideoRepository;
    private final RepostByUserRepository repostByUserRepository;
    private final VideoCountersRepository videoCountersRepository;
    private final CounterCacheService counterCacheService;
    private final InteractionEventPublisher eventPublisher;
    private final InteractionRateLimiter rateLimiter;
    private final VideoOwnershipClient videoOwnershipClient;
    private final FriendshipClient friendshipClient;

    @Override
    public RepostStatusResponse repost(Long videoId, Long currentUserId) {
        videoOwnershipClient.requireVisible(videoId);
        rateLimiter.require("repost-rate", videoId, currentUserId, RepostRateLimitedException::new);

        long repostCount = counterCacheService.getCounts(videoId).repostCount();

        // Truncated to what Cassandra stores, so a retry can recognise the row as this call's own.
        Instant repostedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        RepostByVideoKey key = RepostByVideoKey.builder().videoId(videoId).userId(currentUserId).build();
        boolean newlyReposted = LwtRetry.run(
                () -> repostByVideoRepository.insertIfNotExists(videoId, currentUserId, repostedAt),
                () -> repostByVideoRepository.findById(key)
                        .filter(row -> repostedAt.equals(row.getCreatedAt())).isPresent(),
                "Repost/un-repost");

        if (newlyReposted) {
            boolean countered = false;
            boolean listed = false;
            try {
                videoCountersRepository.incrementRepostCount(videoId, 1);
                countered = true;
                repostByUserRepository.save(repostByUserRow(videoId, currentUserId, repostedAt));
                listed = true;
                counterCacheService.invalidate(videoId);
                eventPublisher.publishRepost(videoId, currentUserId, true);
            } catch (RuntimeException ex) {
                String what = "repost of video %d by user %d".formatted(videoId, currentUserId);
                if (countered) {
                    undo(() -> videoCountersRepository.incrementRepostCount(videoId, -1), what, ex);
                }
                if (listed) {
                    undo(() -> repostByUserRepository.deleteById(
                            repostByUserKey(videoId, currentUserId, repostedAt)), what, ex);
                }
                undo(() -> repostByVideoRepository.deleteIfExists(videoId, currentUserId), what, ex);
                throw ex;
            }
            repostCount++;
        }

        return new RepostStatusResponse(videoId, true, repostCount);
    }

    @Override
    public RepostStatusResponse unrepost(Long videoId, Long currentUserId) {
        long repostCount = counterCacheService.getCounts(videoId).repostCount();

        RepostByVideo claim = repostByVideoRepository
                .findById(RepostByVideoKey.builder().videoId(videoId).userId(currentUserId).build())
                .orElse(null);
        if (claim == null) {
            return new RepostStatusResponse(videoId, false, Math.max(repostCount, 0));
        }

        rateLimiter.require("repost-rate", videoId, currentUserId, RepostRateLimitedException::new);

        Instant repostedAt = claim.getCreatedAt();

        boolean wasReposted = LwtRetry.runDelete(
                () -> repostByVideoRepository.deleteIfExists(videoId, currentUserId), "Repost/un-repost");

        if (wasReposted) {
            boolean countered = false;
            boolean delisted = false;
            try {
                videoCountersRepository.incrementRepostCount(videoId, -1);
                countered = true;
                repostByUserRepository.deleteById(repostByUserKey(videoId, currentUserId, repostedAt));
                delisted = true;
                counterCacheService.invalidate(videoId);
                eventPublisher.publishRepost(videoId, currentUserId, false);
            } catch (RuntimeException ex) {
                String what = "un-repost of video %d by user %d".formatted(videoId, currentUserId);
                if (countered) {
                    undo(() -> videoCountersRepository.incrementRepostCount(videoId, 1), what, ex);
                }
                if (delisted) {
                    undo(() -> repostByUserRepository.save(
                            repostByUserRow(videoId, currentUserId, repostedAt)), what, ex);
                }
                undo(() -> repostByVideoRepository.insertIfNotExists(videoId, currentUserId, repostedAt),
                        what, ex);
                throw ex;
            }
            repostCount--;
        }

        return new RepostStatusResponse(videoId, false, Math.max(repostCount, 0));
    }

    @Override
    public List<RepostContextResponse> getContexts(List<Long> videoIds, Long currentUserId) {
        List<Long> ids = videoIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .limit(MAX_BATCH_SIZE)
                .toList();
        if (ids.isEmpty()) {
            return List.of();
        }

        // One query for the page rather than one per card. Grouping keeps each video's reposters
        // in the order its partition returned them, which is what the per-video read answered.
        Map<Long, List<Long>> reposters = repostByVideoRepository.findByVideoIdIn(ids, RECENT_REPOSTERS_LIMIT)
                .stream()
                .collect(Collectors.groupingBy(row -> row.getKey().getVideoId(),
                        Collectors.mapping(row -> row.getKey().getUserId(), Collectors.toList())));
        Set<Long> repostedByMe = repostedBy(currentUserId, ids);
        return ids.stream()
                .map(videoId -> new RepostContextResponse(videoId, repostedByMe.contains(videoId),
                        reposters.getOrDefault(videoId, List.of())))
                .toList();
    }

    /**
     * Which of these videos the viewer reposted, asked of the table rather than read off the
     * reposter sample. The sample is the first twenty by user_id, so anyone outside it was told
     * they had not reposted a video they had, and the badge offered Repost instead of Remove.
     * One query for the page.
     */
    private Set<Long> repostedBy(Long userId, List<Long> videoIds) {
        if (userId == null || videoIds.isEmpty()) {
            return Set.of();
        }
        return repostByVideoRepository.findByVideoIdInAndUserId(videoIds, userId).stream()
                .map(row -> row.getKey().getVideoId())
                .collect(Collectors.toSet());
    }

    @Override
    public VideoIdPageResponse listReposts(Long viewerId, Long userId, String cursor, int size) {
        // Refused outright rather than answered empty, so the client can say why.
        if (!userId.equals(viewerId) && !friendshipClient.canSeeFollowerContent(userId, viewerId)) {
            throw new PrivateAccountException();
        }

        CassandraPageRequest pageRequest = CassandraCursors.decode(cursor, size, InvalidCursorException::new);
        try {
            Slice<RepostByUser> slice = repostByUserRepository.findByUserId(userId, pageRequest);
            return CassandraCursors.page(slice, repost -> repost.getKey().getVideoId());
        } catch (CassandraInvalidQueryException e) {
            throw new InvalidCursorException();
        }
    }

    private static RepostByUserKey repostByUserKey(Long videoId, Long userId, Instant repostedAt) {
        return RepostByUserKey.builder().userId(userId).createdAt(repostedAt).videoId(videoId).build();
    }

    private static RepostByUser repostByUserRow(Long videoId, Long userId, Instant repostedAt) {
        return RepostByUser.builder().key(repostByUserKey(videoId, userId, repostedAt)).build();
    }

    private void undo(Runnable compensation, String what, RuntimeException cause) {
        try {
            compensation.run();
        } catch (RuntimeException ex) {
            log.error("Could not undo the {} after {}; its counter is now off by one",
                    what, cause.getMessage(), ex);
        }
    }
}
