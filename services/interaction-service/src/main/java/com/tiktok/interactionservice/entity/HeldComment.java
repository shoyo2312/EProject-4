package com.tiktok.interactionservice.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.cassandra.core.mapping.PrimaryKey;
import org.springframework.data.cassandra.core.mapping.Table;

/**
 * One id in a video owner's queue of held comments, newest first — what "comments to review" reads,
 * one partition per video. Ids only, like {@link CommentIndex}: {@code comments_by_video} stays the
 * source of truth, so a row here that outlived its comment's approval or deletion is skipped by the
 * reader rather than trusted.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("held_comments_by_video")
public class HeldComment {

    @PrimaryKey
    private HeldCommentKey key;

    public static HeldComment of(Long videoId, Long commentId) {
        return HeldComment.builder()
                .key(HeldCommentKey.builder().videoId(videoId).commentId(commentId).build())
                .build();
    }
}
