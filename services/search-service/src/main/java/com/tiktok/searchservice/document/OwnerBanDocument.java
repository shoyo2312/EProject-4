package com.tiktok.searchservice.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

/** One account's ban, for the search query to leave its videos out — see {@code OwnerBans}. */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(indexName = "owner_bans")
public class OwnerBanDocument {

    /** The userId, as the document id. */
    @Id
    private String id;

    @Field(type = FieldType.Long)
    private Long userId;

    /** Epoch millis; absent once lifted, {@code Long.MAX_VALUE} for a ban with no end. */
    @Field(type = FieldType.Long)
    private Long bannedUntil;

    /** Epoch millis of the decision, so an older one arriving later changes nothing. */
    @Field(type = FieldType.Long)
    private Long changedAt;

    /**
     * Epoch millis the owner closed the account; absent while it is open. Its own field and its own
     * guard beside the ban's, so neither an unban nor a reopening undoes the other decision.
     */
    @Field(type = FieldType.Long)
    private Long deactivatedAt;

    @Field(type = FieldType.Long)
    private Long accountStateChangedAt;
}
