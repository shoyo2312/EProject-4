package com.tiktok.interactionservice.service;

import com.datastax.oss.driver.api.core.servererrors.WriteTimeoutException;
import com.tiktok.interactionservice.client.VideoOwnershipClient;
import com.tiktok.interactionservice.dto.response.SaveStatusResponse;
import com.tiktok.interactionservice.dto.response.VideoIdPageResponse;
import com.tiktok.interactionservice.entity.SaveByUser;
import com.tiktok.interactionservice.entity.SaveByUserKey;
import com.tiktok.interactionservice.entity.SaveByUserTime;
import com.tiktok.interactionservice.entity.SaveByUserTimeKey;
import com.tiktok.interactionservice.event.producer.InteractionEventPublisher;
import com.tiktok.interactionservice.exception.InteractionConflictException;
import com.tiktok.interactionservice.exception.InvalidCursorException;
import com.tiktok.interactionservice.exception.SaveRateLimitedException;
import com.tiktok.interactionservice.repository.SaveByUserRepository;
import com.tiktok.interactionservice.repository.SaveByUserTimeRepository;
import com.tiktok.interactionservice.repository.VideoCountersRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.cassandra.CassandraInvalidQueryException;
import org.springframework.data.cassandra.core.query.CassandraPageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * Favourites. Two tables, same shape as the like pair but for a different reason: saves_by_user is
 * the claim and the status read, saves_by_user_time is the listing in save order. The LWT on the
 * claim is what makes the pair safe — it grants the right to write the listing row exactly once,
 * so a retried save cannot leave the same video in the listing twice.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SaveServiceImpl implements SaveService {

    private static final int MAX_LWT_RETRIES = 2;

    private final SaveByUserRepository saveByUserRepository;
    private final SaveByUserTimeRepository saveByUserTimeRepository;
    private final InteractionRateLimiter rateLimiter;
    private final VideoCountersRepository videoCountersRepository;
    private final CounterCacheService counterCacheService;
    private final InteractionEventPublisher eventPublisher;
    private final VideoOwnershipClient videoOwnershipClient;

    @Override
    public SaveStatusResponse save(Long videoId, Long currentUserId) {
        videoOwnershipClient.requireVisible(videoId);
        rateLimiter.require("save-rate", videoId, currentUserId, SaveRateLimitedException::new);

        Instant savedAt = Instant.now();
        boolean newlySaved = executeLwtWithRetry(
                () -> saveByUserRepository.insertIfNotExists(currentUserId, videoId, savedAt));

        if (newlySaved) {
            // The LWT is what grants the right to move the counter, and it grants it exactly
            // once. A failure past this point has to give the claim back, or the save stays
            // stored against a counter that is short by one for good — the same compensation
            // LikeServiceImpl does around its claim.
            boolean countered = false;
            boolean listed = false;
            try {
                videoCountersRepository.incrementSaveCount(videoId, 1);
                countered = true;
                saveByUserTimeRepository.save(listingRow(videoId, currentUserId, savedAt));
                listed = true;
                counterCacheService.invalidate(videoId);
                eventPublisher.publishSave(videoId, currentUserId, true);
            } catch (RuntimeException ex) {
                // Counter first, then the listing row, then the claim, each only if it actually
                // landed. Giving the claim back alone would leave the increment behind, so the
                // client's retry takes a fresh claim and adds a second one for the same save; and
                // a listing row left behind keeps the video in the saved list with no claim.
                if (countered) {
                    undo(() -> videoCountersRepository.incrementSaveCount(videoId, -1),
                            videoId, currentUserId, ex);
                }
                if (listed) {
                    undo(() -> saveByUserTimeRepository.deleteById(listingKey(videoId, currentUserId, savedAt)),
                            videoId, currentUserId, ex);
                }
                undo(() -> saveByUserRepository.deleteIfExists(currentUserId, videoId),
                        videoId, currentUserId, ex);
                throw ex;
            }
        }
        return new SaveStatusResponse(videoId, true);
    }

    @Override
    public SaveStatusResponse unsave(Long videoId, Long currentUserId) {
        // Read for created_at first: it addresses the listing row, and once the claim is gone
        // there is nothing left that remembers it. A concurrent unsave that reads the same value
        // simply loses the LWT below and stops.
        Optional<SaveByUser> claim = saveByUserRepository.findById(
                SaveByUserKey.builder().userId(currentUserId).videoId(videoId).build());
        if (claim.isEmpty()) {
            // Nothing to undo, so nothing to charge for: the limit guards the LWT below, and an
            // unsave of a video that was never saved never reaches it.
            return new SaveStatusResponse(videoId, false);
        }
        Instant savedAt = claim.get().getCreatedAt();

        rateLimiter.require("save-rate", videoId, currentUserId, SaveRateLimitedException::new);

        boolean wasSaved = executeLwtWithRetry(() -> saveByUserRepository.deleteIfExists(currentUserId, videoId));
        if (wasSaved) {
            boolean countered = false;
            boolean delisted = false;
            try {
                videoCountersRepository.incrementSaveCount(videoId, -1);
                countered = true;
                saveByUserTimeRepository.deleteById(listingKey(videoId, currentUserId, savedAt));
                delisted = true;
                counterCacheService.invalidate(videoId);
                eventPublisher.publishSave(videoId, currentUserId, false);
            } catch (RuntimeException ex) {
                if (countered) {
                    undo(() -> videoCountersRepository.incrementSaveCount(videoId, 1),
                            videoId, currentUserId, ex);
                }
                if (delisted) {
                    undo(() -> saveByUserTimeRepository.save(listingRow(videoId, currentUserId, savedAt)),
                            videoId, currentUserId, ex);
                }
                // The original timestamp, not a fresh one: the restored claim has to keep
                // addressing the listing row.
                undo(() -> saveByUserRepository.insertIfNotExists(currentUserId, videoId, savedAt),
                        videoId, currentUserId, ex);
                throw ex;
            }
        }
        return new SaveStatusResponse(videoId, false);
    }

    @Override
    public SaveStatusResponse getStatus(Long videoId, Long currentUserId) {
        boolean saved = saveByUserRepository.existsById(
                SaveByUserKey.builder().userId(currentUserId).videoId(videoId).build());
        return new SaveStatusResponse(videoId, saved);
    }

    @Override
    public VideoIdPageResponse listSavedVideos(Long currentUserId, String cursor, int size) {
        CassandraPageRequest pageRequest = CassandraCursors.decode(cursor, size, InvalidCursorException::new);
        try {
            Slice<SaveByUserTime> slice = saveByUserTimeRepository.findByUserId(currentUserId, pageRequest);
            return CassandraCursors.page(slice, save -> save.getKey().getVideoId());
        } catch (CassandraInvalidQueryException e) {
            // Base64 that decodes into bytes Cassandra will not accept as paging state gets past
            // the decoder and is only refused here, by the coordinator.
            throw new InvalidCursorException();
        }
    }

    /**
     * Same reasoning as {@link LikeServiceImpl}: a WriteTimeoutException leaves an LWT's outcome
     * indeterminate, and IF NOT EXISTS / IF EXISTS is idempotent, so retrying it is safe.
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
                "Save/unsave could not be confirmed after retries, please try again");
        failure.initCause(lastError);
        throw failure;
    }

    private static SaveByUserTimeKey listingKey(Long videoId, Long userId, Instant savedAt) {
        return SaveByUserTimeKey.builder().userId(userId).createdAt(savedAt).videoId(videoId).build();
    }

    private static SaveByUserTime listingRow(Long videoId, Long userId, Instant savedAt) {
        return SaveByUserTime.builder().key(listingKey(videoId, userId, savedAt)).build();
    }

    /** Swallowed and logged: an exception is already on its way to the caller and it is the one worth reporting. */
    private void undo(Runnable compensation, Long videoId, Long currentUserId, RuntimeException cause) {
        try {
            compensation.run();
        } catch (RuntimeException suppressed) {
            log.error("Could not compensate save of video {} by user {}, tables may disagree", videoId, currentUserId, suppressed);
            cause.addSuppressed(suppressed);
        }
    }
}
