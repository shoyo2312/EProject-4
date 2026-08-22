package com.tiktok.interactionservice.service;

import com.tiktok.common.id.SnowflakeIdGenerator;
import com.tiktok.interactionservice.dto.response.CommentPageResponse;
import com.tiktok.interactionservice.dto.response.CommentResponse;
import com.tiktok.interactionservice.entity.CommentByVideo;
import com.tiktok.interactionservice.entity.CommentByVideoKey;
import com.tiktok.interactionservice.event.producer.InteractionEventPublisher;
import com.tiktok.interactionservice.exception.CommentNotFoundException;
import com.tiktok.interactionservice.exception.NotCommentOwnerException;
import com.tiktok.interactionservice.mapper.CommentMapper;
import com.tiktok.interactionservice.repository.CommentByVideoRepository;
import com.tiktok.interactionservice.repository.VideoCountersRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.cassandra.core.query.CassandraPageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CommentServiceImpl implements CommentService {

    /** How many all-deleted pages to skip past before handing the client a cursor and giving up. */
    private static final int MAX_PAGES_SCANNED = 5;

    private final CommentByVideoRepository commentByVideoRepository;
    private final VideoCountersRepository videoCountersRepository;
    private final CounterCacheService counterCacheService;
    private final CommentMapper commentMapper;
    private final InteractionEventPublisher eventPublisher;

    @Override
    public CommentResponse addComment(Long videoId, Long currentUserId, String content) {
        Long commentId = SnowflakeIdGenerator.nextId();

        CommentByVideo comment = CommentByVideo.builder()
                .key(CommentByVideoKey.builder().videoId(videoId).commentId(commentId).build())
                .userId(currentUserId)
                .content(content)
                .createdAt(Instant.now())
                .build();
        commentByVideoRepository.save(comment);

        videoCountersRepository.incrementCommentCount(videoId, 1);
        counterCacheService.invalidate(videoId);
        eventPublisher.publishCommentCreated(commentId, videoId, currentUserId, content);

        return commentMapper.toResponse(comment);
    }

    /**
     * Deleted comments are filtered after Cassandra has already cut the page, so a page whose rows
     * were all deleted comes back empty while hasMore says otherwise — a client that stops on an
     * empty page stops early, and one that does not has to loop itself. Skipping such pages here
     * keeps that loop in one place.
     *
     * <p>ponytail: bounded to a few pages, so a video with thousands of consecutive deleted
     * comments can still return an empty page rather than scan forever. Filtering in the query is
     * the real fix, and Cassandra cannot do it without a secondary index nobody wants.
     */
    @Override
    public CommentPageResponse listComments(Long videoId, String cursor, int size) {
        CassandraPageRequest pageRequest = decodeCursor(cursor, size);

        List<CommentResponse> items = List.of();
        Slice<CommentByVideo> slice;

        for (int page = 0; page < MAX_PAGES_SCANNED; page++) {
            slice = commentByVideoRepository.findByVideoId(videoId, pageRequest);

            items = slice.getContent().stream()
                    .filter(comment -> !comment.isDeleted())
                    .map(commentMapper::toResponse)
                    .toList();

            boolean hasMore = slice.hasNext();
            String nextCursor = hasMore ? encodeCursor((CassandraPageRequest) slice.nextPageable()) : null;

            if (!items.isEmpty() || !hasMore) {
                return new CommentPageResponse(items, nextCursor, hasMore);
            }
            pageRequest = (CassandraPageRequest) slice.nextPageable();
        }

        return new CommentPageResponse(items, encodeCursor(pageRequest), true);
    }

    @Override
    public void deleteComment(Long videoId, Long commentId, Long currentUserId) {
        CommentByVideoKey key = CommentByVideoKey.builder().videoId(videoId).commentId(commentId).build();
        CommentByVideo comment = commentByVideoRepository.findById(key)
                .filter(c -> !c.isDeleted())
                .orElseThrow(() -> new CommentNotFoundException(commentId));

        if (!comment.getUserId().equals(currentUserId)) {
            throw new NotCommentOwnerException(commentId);
        }

        // The read above answers "may this caller delete it"; it cannot answer "is it still there",
        // because another delete can land between the read and the write. The condition does, and
        // only the caller it applies for is allowed to move the counter — a save() would let both
        // racing deletes decrement, and a counter table has no way back from that.
        boolean deleted = commentByVideoRepository.markDeletedIfNotDeleted(videoId, commentId, Instant.now());
        if (!deleted) {
            throw new CommentNotFoundException(commentId);
        }

        videoCountersRepository.incrementCommentCount(videoId, -1);
        counterCacheService.invalidate(videoId);
    }

    private CassandraPageRequest decodeCursor(String cursor, int size) {
        CassandraPageRequest firstPage = CassandraPageRequest.first(size);
        if (cursor == null || cursor.isBlank()) {
            return firstPage;
        }
        ByteBuffer pagingState = ByteBuffer.wrap(Base64.getUrlDecoder().decode(cursor));
        return CassandraPageRequest.of(firstPage, pagingState);
    }

    private String encodeCursor(CassandraPageRequest pageRequest) {
        ByteBuffer pagingState = pageRequest.getPagingState();
        byte[] bytes = new byte[pagingState.remaining()];
        pagingState.duplicate().get(bytes);
        return Base64.getUrlEncoder().encodeToString(bytes);
    }
}
