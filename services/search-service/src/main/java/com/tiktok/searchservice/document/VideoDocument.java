package com.tiktok.searchservice.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;
import org.springframework.data.elasticsearch.annotations.InnerField;
import org.springframework.data.elasticsearch.annotations.MultiField;
import org.springframework.data.elasticsearch.annotations.Setting;

import java.time.Instant;
import java.util.List;

/**
 * Search projection of a video, built from Kafka events rather than owning writes —
 * search-service never calls video-service's DB directly.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(indexName = "videos")
@Setting(settingPath = "es/videos-settings.json")
public class VideoDocument {

    /**
     * Name of the sub-field that holds the prefix-analysed copy of {@code title} and
     * {@code description}. Also what IndexBootstrap looks for to tell an index built with this
     * mapping from one built before it.
     */
    public static final String PREFIX_SUBFIELD = "prefix";

    @Id
    private String id;

    @Field(type = FieldType.Long)
    private Long userId;

    /**
     * Two analyses of the same text. The main field is standard-analysed: whole words, for the
     * exact hits that should rank first. {@code prefix} is edge-n-grammed and split on
     * punctuation, case and letter/digit boundaries, so "video_" (or "vid", or "Video1") still
     * reaches a video called "video_1" — the standard tokenizer keeps "video_1" as one token,
     * which is why a search for anything short of the full title used to find nothing.
     */
    @MultiField(
            mainField = @Field(type = FieldType.Text),
            otherFields = @InnerField(suffix = PREFIX_SUBFIELD, type = FieldType.Text,
                    analyzer = "prefix_index", searchAnalyzer = "prefix_search"))
    private String title;

    @MultiField(
            mainField = @Field(type = FieldType.Text),
            otherFields = @InnerField(suffix = PREFIX_SUBFIELD, type = FieldType.Text,
                    analyzer = "prefix_index", searchAnalyzer = "prefix_search"))
    private String description;

    @Field(type = FieldType.Keyword)
    private String thumbnailUrl;

    @Field(type = FieldType.Keyword)
    private String status;

    /**
     * PUBLIC, FRIENDS or PRIVATE, from the publication event and kept current by
     * VideoVisibilityChangedEvent. Status is not a substitute for it: a video that has cleared
     * moderation is PUBLISHED whatever its owner set here, so a query filtering on status alone
     * hands a private video to anyone who searches. Absent only on documents indexed before this
     * field existed — see SearchServiceImpl for how those are treated.
     */
    @Field(type = FieldType.Keyword)
    private String visibility;

    /**
     * Where a transcode result parks itself when it arrives before the publication that carries
     * the video's content — see SearchIndexWriter. Also what a moderation restore reads to put
     * back the status the takedown interrupted, rather than assuming PUBLISHED.
     */
    @Field(type = FieldType.Keyword)
    private String pendingStatus;

    @Field(type = FieldType.Integer)
    private Integer durationSeconds;

    /**
     * Hashtags, already normalised by video-service (lowercased, {@code #} stripped) — so a
     * keyword field rather than text: a tag filter is an exact match on the whole tag, not an
     * analysed match that would let "dancing" hit "dance". Never null; an untagged video is empty.
     */
    @Field(type = FieldType.Keyword)
    private List<String> tags;

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
}
