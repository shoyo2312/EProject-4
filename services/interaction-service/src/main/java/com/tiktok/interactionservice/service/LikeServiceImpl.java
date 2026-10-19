package com.tiktok.interactionservice.service;

import com.tiktok.interactionservice.client.VideoOwnershipClient;
import com.tiktok.interactionservice.dto.response.LikeStatusResponse;
import com.tiktok.interactionservice.dto.response.VideoIdPageResponse;
import com.tiktok.interactionservice.entity.LikeByUser;
import com.tiktok.interactionservice.entity.LikeByUserKey;
import com.tiktok.interactionservice.entity.LikeByVideo;
import com.tiktok.interactionservice.entity.LikeByVideoKey;
import com.tiktok.interactionservice.event.producer.InteractionEventPublisher;
import com.tiktok.interactionservice.exception.InvalidCursorException;
import com.tiktok.interactionservice.exception.LikeRateLimitedException;
import com.tiktok.interactionservice.repository.LikeByUserRepository;
import com.tiktok.interactionservice.repository.LikeByVideoRepository;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class LikeServiceImpl implements LikeService {

    /** Same ceiling the paged listings use — one feed page's worth of cards. */
    private static final int MAX_BATCH_SIZE = 50;

    private final LikeByVideoRepository likeByVideoRepository;
    private final LikeByUserRepository likeByUserRepository;
    private final VideoCountersRepository videoCountersRepository;
    private final CounterCacheService counterCacheService;
    private final InteractionEventPublisher eventPublisher;
    private final InteractionRateLimiter rateLimiter;
    private final VideoOwnershipClient videoOwnershipClient;

    @Override
    public LikeStatusResponse like(Long videoId, Long currentUserId) {
        videoOwnershipClient.requireVisible(videoId);
        rateLimiter.require("like-rate", videoId, currentUserId, LikeRateLimitedException::new);

        // Read before the write, and add the delta here rather than reading again afterwards. A
        // Cassandra counter read is not guaranteed to see the increment that just happened, and
        // the read after an invalidate is the one that repopulates the cache — so a stale value
        // would not merely be returned once, it would be pinned there for the cache's whole TTL.
        long likeCount = counterCacheService.getCounts(videoId).likeCount();

        // One timestamp for both tables, not Instant.now() twice: it is the reverse index's
        // clustering key, so unlike addresses that row by the value stored on the claim. Two
        // different instants and the unlike would delete nothing.
        // Truncated to what Cassandra stores, so a retry can recognise the row as this call's own.
        Instant likedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        LikeByVideoKey key = LikeByVideoKey.builder().videoId(videoId).userId(currentUserId).build();
        boolean newlyLiked = LwtRetry.run(
                () -> likeByVideoRepository.insertIfNotExists(videoId, currentUserId, likedAt),
                () -> likeByVideoRepository.findById(key)
                        .filter(row -> likedAt.equals(row.getCreatedAt())).isPresent(),
                "Like/unlike");

        if (newlyLiked) {
            // The LWT is what grants the right to move the counter, and it grants it exactly once:
            // this caller's retry finds the row already there, is told newlyLiked=false, and never
            // increments. A failure past this point therefore has to give the claim back, or the
            // like stays stored against a counter that is short by one for good — the same
            // compensation ViewServiceImpl does around its play claim.
            boolean countered = false;
            boolean listed = false;
            try {
                videoCountersRepository.incrementLikeCount(videoId, 1);
                countered = true;
                likeByUserRepository.save(likeByUserRow(videoId, currentUserId, likedAt));
                listed = true;
                counterCacheService.invalidate(videoId);
                eventPublisher.publishLike(videoId, currentUserId, true);
            } catch (RuntimeException ex) {
                String what = "like of video %d by user %d".formatted(videoId, currentUserId);
                // The counter first, then the reverse index, then the claim, each only if it
                // actually landed. Giving the claim back alone is what made a failed publish
                // permanent: the increment had already landed, so the client's retry took a fresh
                // claim and added a second one for the same like. The reverse-index row is here
                // for the same reason in the other direction — left behind, it puts the video in
                // the user's liked list with no claim behind it, so listLikedVideos and getStatus
                // disagree for good.
                if (countered) {
                    undo(() -> videoCountersRepository.incrementLikeCount(videoId, -1), what, ex);
                }
                if (listed) {
                    undo(() -> likeByUserRepository.deleteById(
                            likeByUserKey(videoId, currentUserId, likedAt)), what, ex);
                }
                undo(() -> likeByVideoRepository.deleteIfExists(videoId, currentUserId), what, ex);
                throw ex;
            }
            likeCount++;
        }

        return new LikeStatusResponse(videoId, true, likeCount);
    }

    @Override
    public LikeStatusResponse unlike(Long videoId, Long currentUserId) {
        long likeCount = counterCacheService.getCounts(videoId).likeCount();

        // Read the claim for its timestamp before deleting it: that timestamp addresses the
        // reverse-index row, and once the claim is gone nothing remembers it. A concurrent
        // unlike that read the same value simply loses the LWT below and stops.
        LikeByVideo claim = likeByVideoRepository
                .findById(LikeByVideoKey.builder().videoId(videoId).userId(currentUserId).build())
                .orElse(null);
        if (claim == null) {
            return new LikeStatusResponse(videoId, false, Math.max(likeCount, 0));
        }

        rateLimiter.require("like-rate", videoId, currentUserId, LikeRateLimitedException::new);

        Instant likedAt = claim.getCreatedAt();

        boolean wasLiked = LwtRetry.runDelete(
                () -> likeByVideoRepository.deleteIfExists(videoId, currentUserId), "Like/unlike");

        if (wasLiked) {
            // Symmetric with like(): the LWT delete is the one call allowed to decrement, so a
            // failure after it puts the like row back rather than leaving the video counted as
            // liked by someone whose like is gone.
            boolean countered = false;
            boolean delisted = false;
            try {
                videoCountersRepository.incrementLikeCount(videoId, -1);
                countered = true;
                likeByUserRepository.deleteById(likeByUserKey(videoId, currentUserId, likedAt));
                delisted = true;
                counterCacheService.invalidate(videoId);
                eventPublisher.publishLike(videoId, currentUserId, false);
            } catch (RuntimeException ex) {
                String what = "unlike of video %d by user %d".formatted(videoId, currentUserId);
                if (countered) {
                    undo(() -> videoCountersRepository.incrementLikeCount(videoId, 1), what, ex);
                }
                // The reverse-index row goes back too, under the timestamp the claim addresses it
                // by. Without this a failure after the delete — a publish that throws, say —
                // restores the claim over a list entry that is gone, and the video stays liked
                // according to getStatus while listLikedVideos has never heard of it.
                if (delisted) {
                    undo(() -> likeByUserRepository.save(
                            likeByUserRow(videoId, currentUserId, likedAt)), what, ex);
                }
                // The original timestamp, not a fresh one: the restored claim has to keep
                // addressing the reverse-index row restored above.
                undo(() -> likeByVideoRepository.insertIfNotExists(videoId, currentUserId, likedAt),
                        what, ex);
                throw ex;
            }
            likeCount--;
        }

        return new LikeStatusResponse(videoId, false, Math.max(likeCount, 0));
    }

    @Override
    public LikeStatusResponse getStatus(Long videoId, Long currentUserId) {
        boolean liked = currentUserId != null
                && likeByVideoRepository.existsById(LikeByVideoKey.builder().videoId(videoId).userId(currentUserId).build());
        long likeCount = counterCacheService.getCounts(videoId).likeCount();
        return new LikeStatusResponse(videoId, liked, likeCount);
    }

    @Override
    public List<LikeStatusResponse> getStatuses(List<Long> videoIds, Long currentUserId) {
        // Two reads for the whole page — the viewer's likes in one IN, the counters in one batch —
        // where it used to be a Cassandra point read and a cache read per id, one after another.
        // The cap stays here rather than in the controller: the ids arrive in a query string, so
        // nothing else stops one request naming thousands of partitions. Distinct first, so a list
        // padded with one repeated id cannot use up the cap.
        List<Long> ids = videoIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .limit(MAX_BATCH_SIZE)
                .toList();
        if (ids.isEmpty()) {
            return List.of();
        }

        Set<Long> liked = currentUserId == null ? Set.of()
                : likeByVideoRepository.findLikedAmong(ids, currentUserId).stream()
                        .map(row -> row.getKey().getVideoId())
                        .collect(Collectors.toSet());
        Map<Long, VideoCounts> counts = counterCacheService.getCounts(ids);
        return ids.stream()
                .map(videoId -> new LikeStatusResponse(videoId, liked.contains(videoId),
                        counts.getOrDefault(videoId, VideoCounts.ZERO).likeCount()))
                .toList();
    }

    @Override
    public VideoIdPageResponse listLikedVideos(Long currentUserId, String cursor, int size) {
        CassandraPageRequest pageRequest = CassandraCursors.decode(cursor, size, InvalidCursorException::new);
        try {
            // likes_by_user, not likes_by_video: the reverse index exists precisely so this read
            // is one partition rather than a scan of every video's likers.
            Slice<LikeByUser> slice = likeByUserRepository.findByUserId(currentUserId, pageRequest);
            return CassandraCursors.page(slice, like -> like.getKey().getVideoId());
        } catch (CassandraInvalidQueryException e) {
            // Base64 that decodes into bytes Cassandra will not accept as paging state gets past
            // the decoder and is only refused here, by the coordinator.
            throw new InvalidCursorException();
        }
    }

    /**
     * Puts a claim or a counter back after the work behind it failed. Swallowed and logged rather
     * than thrown: an exception is already on its way to the caller and it is the one worth
     * seeing, and a compensation that fails leaves exactly the inconsistency that existed before
     * this method — loud in the log, and no worse than not trying.
     */
    private static LikeByUserKey likeByUserKey(Long videoId, Long userId, Instant likedAt) {
        return LikeByUserKey.builder().userId(userId).createdAt(likedAt).videoId(videoId).build();
    }

    private static LikeByUser likeByUserRow(Long videoId, Long userId, Instant likedAt) {
        return LikeByUser.builder().key(likeByUserKey(videoId, userId, likedAt)).build();
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
