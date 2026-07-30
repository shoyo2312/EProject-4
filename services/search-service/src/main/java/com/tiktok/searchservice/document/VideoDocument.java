package com.tiktok.searchservice.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

import java.time.Instant;

/**
 * Search projection of a video, built from Kafka events rather than owning writes —
 * search-service never calls video-service's DB directly. Fields the source events don't
 * carry yet (description, visibility) stay null/absent until an event supplies them.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(indexName = "videos")
public class VideoDocument {

    @Id
    private String id;

    @Field(type = FieldType.Long)
    private Long userId;

    @Field(type = FieldType.Text)
    private String title;

    @Field(type = FieldType.Text)
    private String description;

    @Field(type = FieldType.Keyword)
    private String thumbnailUrl;

    @Field(type = FieldType.Keyword)
    private String status;

    @Field(type = FieldType.Long)
    private long viewCount;

    @Field(type = FieldType.Long)
    private long likeCount;

    @Field(type = FieldType.Long)
    private long commentCount;

    @Field(type = FieldType.Long)
    private long shareCount;

    @Field(type = FieldType.Date, format = org.springframework.data.elasticsearch.annotations.DateFormat.date_hour_minute_second_millis)
    private Instant createdAt;

    public void markPublished(String thumbnailUrl, Integer durationSeconds) {
        this.status = "PUBLISHED";
        this.thumbnailUrl = thumbnailUrl;
    }

    public void markFailed() {
        this.status = "FAILED";
    }

    public void applyLikeDelta(long delta) {
        this.likeCount = Math.max(0, this.likeCount + delta);
    }

    public void incrementCommentCount() {
        this.commentCount++;
    }

    public void incrementShareCount() {
        this.shareCount++;
    }
}
