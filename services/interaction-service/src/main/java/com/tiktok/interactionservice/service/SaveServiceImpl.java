package com.tiktok.interactionservice.service;

import com.datastax.oss.driver.api.core.servererrors.WriteTimeoutException;
import com.tiktok.interactionservice.dto.response.SaveStatusResponse;
import com.tiktok.interactionservice.dto.response.VideoIdPageResponse;
import com.tiktok.interactionservice.entity.SaveByUser;
import com.tiktok.interactionservice.entity.SaveByUserKey;
import com.tiktok.interactionservice.entity.SaveByUserTime;
import com.tiktok.interactionservice.entity.SaveByUserTimeKey;
import com.tiktok.interactionservice.exception.InteractionConflictException;
import com.tiktok.interactionservice.exception.InvalidCursorException;
import com.tiktok.interactionservice.exception.SaveRateLimitedException;
import com.tiktok.interactionservice.repository.SaveByUserRepository;
import com.tiktok.interactionservice.repository.SaveByUserTimeRepository;
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

    @Override
    public SaveStatusResponse save(Long videoId, Long currentUserId) {
        rateLimiter.require("save-rate", videoId, currentUserId, SaveRateLimitedException::new);

        Instant savedAt = Instant.now();
        boolean newlySaved = executeLwtWithRetry(
                () -> saveByUserRepository.insertIfNotExists(currentUserId, videoId, savedAt));

        if (newlySaved) {
            try {
                saveByUserTimeRepository.save(SaveByUserTime.builder()
                        .key(SaveByUserTimeKey.builder()
                                .userId(currentUserId)
                                .createdAt(savedAt)
                                .videoId(videoId)
                                .build())
                        .build());
            } catch (RuntimeException ex) {
                // Give the claim back, or the video is saved as far as the status check is
                // concerned and missing from the listing for good: the client's retry finds the
                // claim already taken and never writes the listing row.
                undo(() -> saveByUserRepository.deleteIfExists(currentUserId, videoId), videoId, currentUserId, ex);
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
            try {
                saveByUserTimeRepository.deleteById(SaveByUserTimeKey.builder()
                        .userId(currentUserId)
                        .createdAt(savedAt)
                        .videoId(videoId)
                        .build());
            } catch (RuntimeException ex) {
                // Put the claim back so the two tables still agree: saved, and listed.
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
