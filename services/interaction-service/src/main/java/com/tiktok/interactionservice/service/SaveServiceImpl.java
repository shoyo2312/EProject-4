package com.tiktok.interactionservice.service;

import com.datastax.oss.driver.api.core.servererrors.WriteTimeoutException;
import com.tiktok.interactionservice.dto.response.SaveStatusResponse;
import com.tiktok.interactionservice.dto.response.VideoIdPageResponse;
import com.tiktok.interactionservice.entity.SaveByUser;
import com.tiktok.interactionservice.entity.SaveByUserKey;
import com.tiktok.interactionservice.exception.InteractionConflictException;
import com.tiktok.interactionservice.exception.InvalidCursorException;
import com.tiktok.interactionservice.repository.SaveByUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.cassandra.CassandraInvalidQueryException;
import org.springframework.data.cassandra.core.query.CassandraPageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.function.BooleanSupplier;

/**
 * Favourites. Far simpler than {@link LikeServiceImpl} on purpose: a save moves no counter and
 * publishes no event, so the LWT is the whole write and there is nothing downstream of it that a
 * failure would have to compensate for.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SaveServiceImpl implements SaveService {

    private static final int MAX_LWT_RETRIES = 2;

    private final SaveByUserRepository saveByUserRepository;

    @Override
    public SaveStatusResponse save(Long videoId, Long currentUserId) {
        executeLwtWithRetry(() -> saveByUserRepository.insertIfNotExists(currentUserId, videoId, Instant.now()));
        return new SaveStatusResponse(videoId, true);
    }

    @Override
    public SaveStatusResponse unsave(Long videoId, Long currentUserId) {
        executeLwtWithRetry(() -> saveByUserRepository.deleteIfExists(currentUserId, videoId));
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
            Slice<SaveByUser> slice = saveByUserRepository.findByUserId(currentUserId, pageRequest);
            return VideoIdPageResponse.from(slice, save -> save.getKey().getVideoId());
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
    private void executeLwtWithRetry(BooleanSupplier lwtOperation) {
        WriteTimeoutException lastError = null;
        for (int attempt = 0; attempt <= MAX_LWT_RETRIES; attempt++) {
            try {
                lwtOperation.getAsBoolean();
                return;
            } catch (WriteTimeoutException e) {
                lastError = e;
            }
        }
        InteractionConflictException failure = new InteractionConflictException(
                "Save/unsave could not be confirmed after retries, please try again");
        failure.initCause(lastError);
        throw failure;
    }
}
