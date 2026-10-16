package com.tiktok.moderation.classifier;

import com.tiktok.common.http.PooledRequestFactory;
import com.tiktok.moderation.Category;
import com.tiktok.moderation.Decision;
import com.tiktok.moderation.Flag;
import com.tiktok.moderation.StubHttpServer;
import com.tiktok.moderation.StubHttpServer.Reply;
import com.tiktok.moderation.Surface;
import com.tiktok.moderation.TextVerdict;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TextClassifierTest {

    private static final TextVerdict CLEAN = TextVerdict.clean("rules-t1");

    private static final String HATE_HOLD = """
            {"results": [{"verdict": "HOLD", "label": "HATE", "score": 0.934,
                          "scores": {"CLEAN": 0.03, "OFFENSIVE": 0.04, "HATE": 0.934}}],
             "model": "visolex/phobert-v2-hsd", "modelVersion": "hsd-v1", "processingTimeMs": 12}
            """;

    private StubHttpServer server;
    private SimpleMeterRegistry registry;
    private MutableClock clock;

    @BeforeEach
    void start() throws Exception {
        server = new StubHttpServer();
        registry = new SimpleMeterRegistry();
        Metrics.addRegistry(registry);
        clock = new MutableClock(Instant.parse("2026-10-14T10:00:00Z"));
    }

    @AfterEach
    void stop() {
        server.close();
        Metrics.removeRegistry(registry);
    }

    private TextClassifier classifier(boolean enforcing) {
        Duration timeout = Duration.ofMillis(500);
        RestClient restClient = RestClient.builder()
                .baseUrl(server.baseUrl())
                .requestFactory(PooledRequestFactory.create(timeout, timeout))
                .build();
        return new TextClassifier(restClient, enforcing, Duration.ofSeconds(30), clock);
    }

    @Test
    void aHoldWhileEnforcing_isAModelHold_andTheVersionNamesTheModel() {
        server.answer(request -> Reply.ok(HATE_HOLD));

        TextVerdict verdict = classifier(true).refine("bọn này nên biến khỏi đây", Surface.COMMENT, CLEAN);

        assertThat(verdict.decision()).isEqualTo(Decision.HOLD);
        assertThat(verdict.version()).isEqualTo("rules-t1+hsd-v1");
        assertThat(verdict.flags()).singleElement().satisfies(flag -> {
            assertThat(flag.category()).isEqualTo(Category.HATE);
            assertThat(flag.severity()).isEqualTo(Flag.HOLD);
            assertThat(flag.source()).isEqualTo(Flag.Source.MODEL);
            assertThat(flag.rule()).isEqualTo("model:HATE:0.93");
        });
        assertThat(server.received()).singleElement().satisfies(request -> {
            assertThat(request.method()).isEqualTo("POST");
            assertThat(request.path()).isEqualTo("/moderate/text");
            assertThat(request.body()).contains("\"texts\"").contains("bọn này nên biến khỏi đây");
        });
        assertThat(registry.get("text.classifier.opinions")
                .tags("surface", "comment", "label", "hate", "verdict", "hold", "enforced", "true")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void aHoldWhileOnlyMeasuring_isANote_thatDecidesNothing() {
        server.answer(request -> Reply.ok(HATE_HOLD));

        TextVerdict verdict = classifier(false).refine("bọn này nên biến khỏi đây", Surface.COMMENT, CLEAN);

        assertThat(verdict.decision()).isEqualTo(Decision.ALLOW);
        assertThat(verdict.flags()).extracting(Flag::severity).containsExactly(Flag.NOTE);
        assertThat(verdict.summary()).isEqualTo("HATE:model:HATE:0.93");
    }

    /** Offence below the threshold is worth recording; it is what the threshold gets tuned on. */
    @Test
    void offenceBelowTheThreshold_isANote_andCleanIsNothing() {
        server.answer(request -> Reply.ok("""
                {"results": [{"verdict": "ALLOW", "label": "OFFENSIVE", "score": 0.81},
                             {"verdict": "ALLOW", "label": "CLEAN", "score": 0.99}],
                 "modelVersion": "hsd-v1"}
                """));

        List<TextVerdict> verdicts = classifier(true).refine(List.of(
                new TextClassifier.Input("đẹp vãi", Surface.VIDEO_TITLE, CLEAN),
                new TextClassifier.Input("video hay", Surface.VIDEO_DESCRIPTION, CLEAN)));

        assertThat(verdicts.get(0).decision()).isEqualTo(Decision.ALLOW);
        assertThat(verdicts.get(0).flags()).singleElement().satisfies(flag -> {
            assertThat(flag.severity()).isEqualTo(Flag.NOTE);
            assertThat(flag.category()).isEqualTo(Category.PROFANITY);
        });
        assertThat(verdicts.get(1).flags()).isEmpty();
        assertThat(verdicts.get(1).version()).isEqualTo("rules-t1+hsd-v1");
    }

    @Test
    void blankTexts_areNotSent_andComeBackUnchanged() {
        server.answer(request -> Reply.ok("""
                {"results": [{"verdict": "ALLOW", "label": "CLEAN", "score": 0.99}], "modelVersion": "hsd-v1"}
                """));

        List<TextVerdict> verdicts = classifier(true).refine(List.of(
                new TextClassifier.Input(null, Surface.VIDEO_TITLE, CLEAN),
                new TextClassifier.Input("hay", Surface.VIDEO_DESCRIPTION, CLEAN),
                new TextClassifier.Input("  ", Surface.VIDEO_TAGS, CLEAN)));

        assertThat(verdicts.get(0)).isSameAs(CLEAN);
        assertThat(verdicts.get(2)).isSameAs(CLEAN);
        assertThat(verdicts.get(1).version()).isEqualTo("rules-t1+hsd-v1");
        assertThat(server.received()).singleElement().extracting(StubHttpServer.Received::body)
                .asString().doesNotContain("\"  \"");

        assertThat(classifier(true).refine(null, Surface.COMMENT, CLEAN)).isSameAs(CLEAN);
        assertThat(server.received()).hasSize(1);
    }

    @Test
    void theRulesVerdict_keepsItsFlags() {
        server.answer(request -> Reply.ok(HATE_HOLD));
        TextVerdict rules = TextVerdict.of(List.of(new Flag(Category.SPAM, Flag.HOLD, "link:abc.xyz")), "rules-t1");

        TextVerdict verdict = classifier(true).refine("abc.xyz", Surface.COMMENT, rules);

        assertThat(verdict.flags()).extracting(Flag::rule).containsExactlyInAnyOrder("link:abc.xyz", "model:HATE:0.93");
    }

    /** The rules have run; an unreachable classifier is Phase 1, not every comment held. */
    @Test
    void aFailure_leavesTheVerdictAsTheRulesMadeIt_andStopsAskingForTheCooldown() {
        server.answer(request -> new Reply(500, "{\"detail\": \"boom\"}", 0));
        TextClassifier classifier = classifier(true);

        assertThat(classifier.refine("text", Surface.COMMENT, CLEAN)).isSameAs(CLEAN);
        assertThat(classifier.refine("text", Surface.COMMENT, CLEAN)).isSameAs(CLEAN);
        assertThat(server.received()).as("not asked again inside the cooldown").hasSize(1);
        assertThat(registry.get("text.classifier.unavailable").counter().count()).isEqualTo(1.0);

        server.answer(request -> Reply.ok(HATE_HOLD));
        clock.advance(Duration.ofSeconds(31));
        assertThat(classifier.refine("text", Surface.COMMENT, CLEAN).decision()).isEqualTo(Decision.HOLD);
        assertThat(server.received()).hasSize(2);
    }

    @Test
    void aSlowAnswer_isAFailure() {
        server.answer(request -> new Reply(200, HATE_HOLD, 1500));

        assertThat(classifier(true).refine("text", Surface.COMMENT, CLEAN)).isSameAs(CLEAN);
    }

    @Test
    void anAnswerOfTheWrongLength_isAFailure() {
        server.answer(request -> Reply.ok("""
                {"results": [], "modelVersion": "hsd-v1"}
                """));

        assertThat(classifier(true).refine("text", Surface.COMMENT, CLEAN)).isSameAs(CLEAN);
    }

    @Test
    void aDisabledClassifier_isNeverAsked() {
        TextClassifier disabled = TextClassifier.disabled();

        assertThat(disabled.enabled()).isFalse();
        assertThat(disabled.refine("text", Surface.COMMENT, CLEAN)).isSameAs(CLEAN);
    }

    /** A clock the test moves by hand, for the cooldown. */
    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
