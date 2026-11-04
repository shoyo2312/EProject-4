package com.tiktok.interactionservice.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKey;
import org.springframework.data.cassandra.core.mapping.Table;

/**
 * One comment in its video owner's Studio inbox: every comment on every video they own, newest first.
 * {@code comments_by_video} cannot answer that — it is partitioned by video — so this is the owner's
 * own partition. Ids only, like {@link CommentIndex}: the comment row stays the source of truth, and
 * a row here whose comment was deleted since is skipped by the reader rather than trusted.
 *
 * <p>ponytail: one partition per owner grows for ever. Fine at this platform's size; bucketing the
 * partition by month is the upgrade for an account with millions of comments.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("comments_by_owner")
public class CommentByOwner {

    @PrimaryKey
    private CommentByOwnerKey key;

    @Column("video_id")
    private Long videoId;

    public static CommentByOwner of(Long ownerId, Long videoId, Long commentId) {
        return CommentByOwner.builder()
                .key(CommentByOwnerKey.builder().ownerId(ownerId).commentId(commentId).build())
                .videoId(videoId)
                .build();
    }
}
