package com.tiktok.interactionservice.service;

import com.tiktok.interactionservice.dto.response.AdminCommentResponse;
import com.tiktok.interactionservice.entity.CommentByVideo;
import com.tiktok.interactionservice.repository.CommentByVideoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.cassandra.core.query.CassandraPageRequest;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * The console's read side over a video's comment thread.
 *
 * <p>Per video, because that is the only way Cassandra can be asked: {@code comments_by_video} is
 * partitioned by video id, and a platform-wide "newest comments" listing would need a second table
 * written on every comment. Nothing today justifies that — moderation arrives either from a report,
 * which names its video, or from a video already on screen.
 *
 * <p>Removed comments are included, and the owner's comments-off switch is not consulted: both
 * hide rows from viewers, and hiding them from the admin reviewing the thread is the opposite of
 * the point.
 */
@Service
@RequiredArgsConstructor
public class AdminCommentDirectory {

    private final CommentByVideoRepository commentByVideoRepository;

    /**
     * ponytail: one page, no cursor. A thread longer than {@code size} is truncated rather than
     * paged — add a cursor the way CommentServiceImpl.listComments has one if a real thread
     * outgrows it.
     */
    public List<AdminCommentResponse> listForAdmin(Long videoId, int size) {
        return commentByVideoRepository.findByVideoId(videoId, CassandraPageRequest.of(0, size))
                .getContent().stream()
                .map(AdminCommentDirectory::toResponse)
                .toList();
    }

    private static AdminCommentResponse toResponse(CommentByVideo comment) {
        return new AdminCommentResponse(
                comment.getKey().getCommentId(),
                comment.getKey().getVideoId(),
                comment.getUserId(),
                comment.getContent(),
                comment.getParentId(),
                comment.getReplyToUserId(),
                comment.likeCount(),
                comment.getCreatedAt(),
                comment.getDeletedAt());
    }
}
