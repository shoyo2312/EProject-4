package com.tiktok.recommendationservice.dto.rank;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * What the ranking model is told about one candidate.
 *
 * <p>The wire names are snake_case and deliberately identical to the column names the offline
 * trainer selects in ClickHouse. A ranking model is only as good as the agreement between the
 * numbers it was trained on and the numbers it is served — a feature that means "watches" during
 * training and "watches in the last day" at serving produces a model that scores confidently and
 * wrongly, and nothing in the response looks broken. Renaming a field on one side and not the
 * other should therefore fail loudly rather than quietly line up by position.
 *
 * <p>Every one of these is computed from data this service already fetched for the heuristic
 * path, plus a single extra ZMSCORE for {@code age_hours}, so turning ranking on does not make
 * the feed a heavier Redis client.
 */
public record CandidateFeatures(
        @JsonProperty("video_id") String videoId,

        /** ln(1 + watches). Popularity is long-tailed; the raw count would let one viral video
         *  dominate the split points of every tree. */
        @JsonProperty("log_watches") double logWatches,

        /** Completions over watches, or 0.5 when there are too few watches to mean anything. */
        @JsonProperty("completion_rate") double completionRate,

        /** Hours since publish. Recency is the feature the heuristic could never express: it
         *  ranked a twenty-hour-old video and a fresh one identically on equal stats. */
        @JsonProperty("age_hours") double ageHours,

        /** Sum of this viewer's affinity over the tags this video carries. */
        @JsonProperty("tag_affinity") double tagAffinity,

        /** How many of the viewer's interested tags this video hits — a video matching three of
         *  them is a different proposition from one matching a single tag very strongly. */
        @JsonProperty("tag_overlap") int tagOverlap
) {
}
