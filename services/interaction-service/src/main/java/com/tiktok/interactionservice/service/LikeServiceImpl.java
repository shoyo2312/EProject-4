package com.tiktok.interactionservice.service;

import com.datastax.oss.driver.api.core.servererrors.WriteTimeoutException;
import com.tiktok.interactionservice.dto.response.LikeStatusResponse;
import com.tiktok.interactionservice.dto.response.VideoIdPageResponse;
import com.tiktok.interactionservice.entity.LikeByUser;
import com.tiktok.interactionservice.entity.LikeByUserKey;
import com.tiktok.interactionservice.entity.LikeByVideo;
import com.tiktok.interactionservice.entity.LikeByVideoKey;
import com.tiktok.interactionservice.event.producer.InteractionEventPublisher;
import com.tiktok.interactionservice.exception.InteractionConflictException;
import com.tiktok.interactionservice.exception.InvalidCursorException;
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
import java.util.List;
import java.util.function.BooleanSupplier;

@Slf4j
@Service
@RequiredArgsConstructor
public class LikeServiceImpl implements LikeService {

    private static final int MAX_LWT_RETRIES = 2;

    private final LikeByVideoRepository likeByVideoRepository;
    private final LikeByUserRepository likeByUserRepository;
    private final VideoCountersRepository videoCountersRepository;
    private final CounterCacheService counterCacheService;
    private final InteractionEventPublisher eventPublisher;

    @Override
    public LikeStatusResponse like(Long videoId, Long currentUserId) {
        // Read before the write, and add the delta here rather than reading again afterwards. A
        // Cassandra counter read is not guaranteed to see the increment that just happened, and
        // the read after an invalidate is the one that repopulates the cache — so a stale value
        // would not merely be returned once, it would be pinned there for the cache's whole TTL.
        long likeCount = counterCacheService.getCounts(videoId).likeCount();

        // One timestamp for both tables, not Instant.now() twice: it is the reverse index's
        // clustering key, so unlike addresses that row by the value stored on the claim. Two
        // different instants and the unlike would delete nothing.
        Instant likedAt = Instant.now();
        boolean newlyLiked = executeLwtWithRetry(
                () -> likeByVideoRepository.insertIfNotExists(videoId, currentUserId, likedAt));

        if (newlyLiked) {
            // The LWT is what grants the right to move the counter, and it grants it exactly once:
            // this caller's retry finds the row already there, is told newlyLiked=false, and never
            // increments. A failure past this point therefore has to give the claim back, or the
            // like stays stored against a counter that is short by one for good — the same
            // compensation ViewServiceImpl does around its play claim.
            boolean countered = false;
            try {
                videoCountersRepository.incrementLikeCount(videoId, 1);
                countered = true;
                likeByUserRepository.save(LikeByUser.builder()
                        .key(LikeByUserKey.builder()
                                .userId(currentUserId)
                                .createdAt(likedAt)
                                .videoId(videoId)
                                .build())
                        .build());
                counterCacheService.invalidate(videoId);
                eventPublisher.publishLike(videoId, currentUserId, true);
            } catch (RuntimeException ex) {
                String what = "like of video %d by user %d".formatted(videoId, currentUserId);
                // The counter first, then the claim, and only if the counter actually moved.
                // Giving the claim back alone is what made a failed publish permanent: the
                // increment had already landed, so the client's retry took a fresh claim and
                // added a second one for the same like.
                if (countered) {
                    undo(() -> videoCountersRepository.incrementLikeCount(videoId, -1), what, ex);
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
        Instant likedAt = claim.getCreatedAt();

        boolean wasLiked = executeLwtWithRetry(
                () -> likeByVideoRepository.deleteIfExists(videoId, currentUserId));

        if (wasLiked) {
            // Symmetric with like(): the LWT delete is the one call allowed to decrement, so a
            // failure after it puts the like row back rather than leaving the video counted as
            // liked by someone whose like is gone.
            boolean countered = false;
            try {
                videoCountersRepository.incrementLikeCount(videoId, -1);
                countered = true;
                likeByUserRepository.deleteById(LikeByUserKey.builder()
                        .userId(currentUserId)
                        .createdAt(likedAt)
                        .videoId(videoId)
                        .build());
                counterCacheService.invalidate(videoId);
                eventPublisher.publishLike(videoId, currentUserId, false);
            } catch (RuntimeException ex) {
                String what = "unlike of video %d by user %d".formatted(videoId, currentUserId);
                if (countered) {
                    undo(() -> videoCountersRepository.incrementLikeCount(videoId, 1), what, ex);
                }
                // The original timestamp, not a fresh one: the restored claim has to keep
                // addressing the reverse-index row that is still there.
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
        // One point read per id against Cassandra/Redis rather than a fan-out of HTTP requests
        // through the gateway — the batch endpoint exists to collapse the latter, not the former.
        return videoIds.stream().map(videoId -> getStatus(videoId, currentUserId)).toList();
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
    private void undo(Runnable compensation, String what, RuntimeException cause) {
        try {
            compensation.run();
        } catch (RuntimeException ex) {
            log.error("Could not undo the {} after {}; its counter is now off by one",
                    what, cause.getMessage(), ex);
        }
    }

    /**
     * A WriteTimeoutException on a lightweight transaction leaves the true outcome
     * indeterminate. Retrying the LWT itself is safe (IF NOT EXISTS / IF EXISTS is
     * idempotent by nature); a bare counter increment must never be blindly retried.
     *
     * <p>The last timeout is attached as the cause: what the coordinator said about the failed
     * write is the only thing that explains a conflict nobody can reproduce afterwards.
     */
    private boolean executeLwtWithRetry(BooleanSupplier lwtOperation) {
        WriteTimeoutException lastError = null;
        for (int attempt = 0; attempt <= MAX_LWT_RETRIES; attempt++) {
            try {
                return lwtOperation.getAsBoolean();
            } catch (WriteTimeoutException e) {
                lastError = e;
            }
        }
        InteractionConflictException failure = new InteractionConflictException(
                "Like/unlike could not be confirmed after retries, please try again");
        failure.initCause(lastError);
        throw failure;
    }
}
