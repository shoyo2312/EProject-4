package com.tiktok.recommendationservice.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.recommendationservice.dto.rank.CandidateFeatures;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RankClientTest {

    private static final CandidateFeatures CANDIDATE =
            new CandidateFeatures("vid1", 1.0, 0.5, 2.0, 3.0, 1);

    @Test
    void score_whenRankingIsTurnedOff_doesNotCallAnything() {
        RankClient client = new RankClient("http://localhost:8098", false, 150);

        assertThat(client.score(7L, List.of(CANDIDATE))).isEmpty();
    }

    /**
     * The behaviour the feed depends on. A model outage has to degrade the ordering, not the
     * endpoint — throwing here would turn a down ranker into an empty For You page.
     */
    @Test
    void score_whenTheRankerIsUnreachable_returnsNothingInsteadOfThrowing() {
        // Port 1 is privileged and unbound, so this refuses the connection immediately rather
        // than waiting out a timeout.
        RankClient client = new RankClient("http://localhost:1", true, 150);

        assertThat(client.score(7L, List.of(CANDIDATE))).isEmpty();
    }

    @Test
    void score_withNoCandidates_skipsTheCallEntirely() {
        RankClient client = new RankClient("http://localhost:1", true, 150);

        assertThat(client.score(7L, List.of())).isEmpty();
    }

    /**
     * The names on the wire are the model's column names. They are snake_case because that is
     * what the ClickHouse columns the model was trained on are called, and a mismatch between
     * the two sides produces confident scores from the wrong inputs — the one failure mode that
     * shows up nowhere in logs or status codes. The mirror of this assertion lives in
     * {@code services/rank-service/test_features.py}.
     */
    @Test
    void candidateFeatures_serializeUnderTheColumnNamesTheModelWasTrainedOn() throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> json = new ObjectMapper().convertValue(CANDIDATE, Map.class);

        assertThat(json.keySet()).containsExactlyInAnyOrder(
                "video_id", "log_watches", "completion_rate", "age_hours", "tag_affinity", "tag_overlap");
    }
}
