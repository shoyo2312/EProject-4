package com.tiktok.interactionservice.service;

import com.tiktok.interactionservice.dto.response.ActivityPageResponse;
import com.tiktok.interactionservice.dto.response.ActivityRecordResponse;
import com.tiktok.interactionservice.exception.InvalidCursorException;
import com.tiktok.interactionservice.repository.LikeByUserRepository;
import com.tiktok.interactionservice.repository.RepostByUserRepository;
import com.tiktok.interactionservice.repository.SaveByUserTimeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.cassandra.CassandraInvalidQueryException;
import org.springframework.data.cassandra.core.query.CassandraPageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A user's likes, saves and reposts with their timestamps, read off the same per-user partitions
 * as the public listings — for user-service's "Download your data" archive. No privacy check:
 * the only caller asks on behalf of the account owner, through the internal prefix.
 */
@Service
@RequiredArgsConstructor
public class UserActivityExport {

    private final LikeByUserRepository likeByUserRepository;
    private final SaveByUserTimeRepository saveByUserTimeRepository;
    private final RepostByUserRepository repostByUserRepository;

    public ActivityPageResponse likes(Long userId, String cursor, int size) {
        return read(cursor, size, pageRequest -> page(likeByUserRepository.findByUserId(userId, pageRequest),
                like -> new ActivityRecordResponse(like.getKey().getVideoId(), like.getKey().getCreatedAt())));
    }

    public ActivityPageResponse saves(Long userId, String cursor, int size) {
        return read(cursor, size, pageRequest -> page(saveByUserTimeRepository.findByUserId(userId, pageRequest),
                save -> new ActivityRecordResponse(save.getKey().getVideoId(), save.getKey().getCreatedAt())));
    }

    public ActivityPageResponse reposts(Long userId, String cursor, int size) {
        return read(cursor, size, pageRequest -> page(repostByUserRepository.findByUserId(userId, pageRequest),
                repost -> new ActivityRecordResponse(repost.getKey().getVideoId(), repost.getKey().getCreatedAt())));
    }

    private static ActivityPageResponse read(String cursor, int size,
                                             Function<CassandraPageRequest, ActivityPageResponse> query) {
        Supplier<RuntimeException> unusable = InvalidCursorException::new;
        CassandraPageRequest pageRequest = CassandraCursors.decode(cursor, size, unusable);
        try {
            return query.apply(pageRequest);
        } catch (CassandraInvalidQueryException e) {
            // Same as the public listings: paging state the coordinator refuses is a bad cursor.
            throw unusable.get();
        }
    }

    private static <T> ActivityPageResponse page(Slice<T> slice, Function<T, ActivityRecordResponse> toRecord) {
        boolean hasMore = slice.hasNext();
        return new ActivityPageResponse(
                slice.getContent().stream().map(toRecord).toList(),
                hasMore ? CassandraCursors.encode((CassandraPageRequest) slice.nextPageable()) : null,
                hasMore);
    }
}
