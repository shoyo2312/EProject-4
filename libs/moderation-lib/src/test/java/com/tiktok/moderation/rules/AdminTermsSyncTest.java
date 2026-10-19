package com.tiktok.moderation.rules;

import com.tiktok.common.http.PooledRequestFactory;
import com.tiktok.moderation.Decision;
import com.tiktok.moderation.StubHttpServer;
import com.tiktok.moderation.StubHttpServer.Reply;
import com.tiktok.moderation.Surface;
import com.tiktok.moderation.TextModerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AdminTermsSyncTest {

    private static final Blocklist SHIPPED = Blocklist.parse(List.of(
            "version: t1",
            "lồn | PROFANITY | 2"), "test");

    private StubHttpServer server;
    private TextModerator moderator;
    private AdminTermsSync sync;

    @BeforeEach
    void start() throws Exception {
        server = new StubHttpServer();
        moderator = new TextModerator(SHIPPED, new SpamSignals(List.of(), 5), true);
        Duration timeout = Duration.ofMillis(500);
        RestClient restClient = RestClient.builder()
                .baseUrl(server.baseUrl())
                .requestFactory(PooledRequestFactory.create(timeout, timeout))
                .build();
        sync = new AdminTermsSync(moderator, restClient, Duration.ofMinutes(1));
    }

    @AfterEach
    void stop() {
        sync.stop();
        server.close();
    }

    private static Reply snapshot(long revision, String terms) {
        return Reply.ok("""
                {"success": true, "data": {"revision": %d, "terms": [%s]}}
                """.formatted(revision, terms));
    }

    @Test
    void aSnapshot_isApplied_atItsRevision() {
        server.answer(request -> snapshot(4, """
                {"term": "cút đi", "category": "INSULT", "action": "HOLD"},
                {"term": "lồn", "category": null, "action": "EXEMPT"}
                """));

        sync.refresh();

        assertThat(server.received()).singleElement()
                .extracting(StubHttpServer.Received::path).isEqualTo("/api/v1/admin/internal/blocklist");
        assertThat(moderator.version()).isEqualTo("rules-t1+admin-4");
        assertThat(moderator.screen("cút đi", Surface.COMMENT).decision()).isEqualTo(Decision.HOLD);
        assertThat(moderator.screen("lồn", Surface.COMMENT).decision()).isEqualTo(Decision.ALLOW);
    }

    @Test
    void theSameRevisionAgain_doesNotRebuild() {
        server.answer(request -> snapshot(4, """
                {"term": "cút đi", "category": "INSULT", "action": "HOLD"}
                """));
        sync.refresh();

        // Same revision, different body: proof that it was not read again.
        server.answer(request -> snapshot(4, ""));
        sync.refresh();

        assertThat(moderator.screen("cút đi", Surface.COMMENT).decision()).isEqualTo(Decision.HOLD);
    }

    /** An admin-service a release ahead may name a category this library has never heard of. */
    @Test
    void anUnknownCategoryOrAction_skipsThatTerm_notTheList() {
        server.answer(request -> snapshot(5, """
                {"term": "abc", "category": "SOMETHING_NEW", "action": "HOLD"},
                {"term": "def", "category": "SPAM", "action": "SHADOW"},
                {"term": "cút đi", "category": "INSULT", "action": "REJECT"}
                """));

        sync.refresh();

        assertThat(moderator.screen("abc def", Surface.COMMENT).decision()).isEqualTo(Decision.ALLOW);
        assertThat(moderator.screen("cút đi", Surface.COMMENT).decision()).isEqualTo(Decision.REJECT);
    }

    @Test
    void anUnreachableAdminService_keepsTheLastList() {
        server.answer(request -> snapshot(6, """
                {"term": "cút đi", "category": "INSULT", "action": "HOLD"}
                """));
        sync.refresh();

        server.answer(request -> new Reply(503, "{}", 0));
        sync.refresh();

        assertThat(moderator.version()).isEqualTo("rules-t1+admin-6");
        assertThat(moderator.screen("cút đi", Surface.COMMENT).decision()).isEqualTo(Decision.HOLD);
    }

    @Test
    void beforeTheFirstAnswer_theShippedListAloneApplies() {
        server.answer(request -> new Reply(503, "{}", 0));

        sync.refresh();

        assertThat(moderator.version()).isEqualTo("rules-t1");
        assertThat(moderator.adminRevision()).isEmpty();
    }

    @Test
    void started_itPollsOnItsOwnThread() throws Exception {
        server.answer(request -> snapshot(7, ""));

        sync.start();
        for (int i = 0; i < 50 && moderator.adminRevision().isEmpty(); i++) {
            Thread.sleep(20);
        }

        assertThat(sync.isRunning()).isTrue();
        assertThat(moderator.adminRevision()).hasValue(7);
        sync.stop();
        assertThat(sync.isRunning()).isFalse();
    }
}
