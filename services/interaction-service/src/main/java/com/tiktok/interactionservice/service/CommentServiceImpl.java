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

    @Override
    public CommentPageResponse listComments(Long videoId, String cursor, int size) {
        CassandraPageRequest pageRequest = decodeCursor(cursor, size);

        Slice<CommentByVideo> slice = commentByVideoRepository.findByVideoId(videoId, pageRequest);

        List<CommentResponse> items = slice.getContent().stream()
                .filter(comment -> !comment.isDeleted())
                .map(commentMapper::toResponse)
                .toList();

        boolean hasMore = slice.hasNext();
        String nextCursor = hasMore ? encodeCursor((CassandraPageRequest) slice.nextPageable()) : null;

        return new CommentPageResponse(items, nextCursor, hasMore);
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

        CommentByVideo deleted = CommentByVideo.builder()
                .key(comment.getKey())
                .userId(comment.getUserId())
                .content(comment.getContent())
                .createdAt(comment.getCreatedAt())
                .deletedAt(Instant.now())
                .build();
        commentByVideoRepository.save(deleted);

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
