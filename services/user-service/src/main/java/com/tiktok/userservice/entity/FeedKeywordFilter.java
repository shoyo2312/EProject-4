package com.tiktok.userservice.entity;

import com.tiktok.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * One keyword an account filters out of its feeds. Named apart from moderation-lib's
 * {@code KeywordFilter}, the matcher video-service runs these through. See V21.
 */
@Getter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "feed_keyword_filters")
public class FeedKeywordFilter extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** As typed, sanitized: one word or one hashtag. */
    @Column(name = "keyword", nullable = false, length = 200)
    private String keyword;

    @Column(name = "for_you", nullable = false)
    private boolean forYou;

    @Column(name = "following", nullable = false)
    private boolean following;

    @Column(name = "friends", nullable = false)
    private boolean friends;

    /** Total assignment — validated by the service. */
    public void replaceWith(String keyword, boolean forYou, boolean following, boolean friends) {
        this.keyword = keyword;
        this.forYou = forYou;
        this.following = following;
        this.friends = friends;
    }
}
