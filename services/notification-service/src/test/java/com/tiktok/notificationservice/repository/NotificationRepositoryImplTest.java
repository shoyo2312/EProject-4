package com.tiktok.notificationservice.repository;

import com.tiktok.notificationservice.entity.Notification;
import com.tiktok.notificationservice.entity.NotificationType;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.mongodb.test.autoconfigure.DataMongoTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataMongoTest
@Testcontainers
class NotificationRepositoryImplTest {

    @Container
    @ServiceConnection
    static MongoDBContainer MONGO = new MongoDBContainer("mongo:7");

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private MongoTemplate mongoTemplate;

    private static final Instant T0 = Instant.parse("2026-09-23T10:00:00Z");

    @BeforeEach
    void cleanUp() {
        notificationRepository.deleteAll();
    }

    private Notification save(String id, Long recipientId, Instant createdAt, boolean read) {
        return notificationRepository.save(Notification.builder()
                .id(id)
                .recipientId(recipientId)
                .type(NotificationType.SYSTEM)
                .title("t")
                .body("b")
                .read(read)
                .createdAt(createdAt)
                .build());
    }

    private List<String> ids(List<Notification> page) {
        return page.stream().map(Notification::getId).toList();
    }

    @Test
    void aPageIsTheRecipientsNewestFirst() {
        save("101", 1L, T0, false);
        save("102", 1L, T0.plusSeconds(1), false);
        save("103", 1L, T0.plusSeconds(2), false);
        save("201", 2L, T0.plusSeconds(3), false);

        assertThat(ids(notificationRepository.findPage(1L, null, 2))).containsExactly("103", "102");
    }

    /**
     * Entries stamped in the same millisecond straddling a page boundary. A cursor on createdAt
     * alone either repeats them or, with a strict bound, skips the rest of the tie.
     */
    @Test
    void walkingTheCursorVisitsEveryEntryOnceThroughTies() {
        save("110", 1L, T0, false);
        save("111", 1L, T0.plusSeconds(1), false);
        save("112", 1L, T0.plusSeconds(1), false);
        save("113", 1L, T0.plusSeconds(1), false);
        save("114", 1L, T0.plusSeconds(2), false);

        List<String> walked = new ArrayList<>();
        Notification after = null;
        List<Notification> page;
        do {
            page = notificationRepository.findPage(1L, after, 2);
            walked.addAll(ids(page));
            after = page.isEmpty() ? null : page.get(page.size() - 1);
        } while (page.size() == 2);

        assertThat(walked).containsExactly("114", "113", "112", "111", "110");
    }

    @Test
    void markAllReadChangesOnlyThatInboxsUnreadEntries() {
        save("101", 1L, T0, false);
        save("102", 1L, T0.plusSeconds(1), false);
        save("103", 1L, T0.plusSeconds(2), true);
        save("201", 2L, T0, false);

        assertThat(notificationRepository.markAllRead(1L)).isEqualTo(2);

        assertThat(notificationRepository.countByRecipientIdAndReadFalse(1L)).isZero();
        assertThat(notificationRepository.countByRecipientIdAndReadFalse(2L)).isEqualTo(1);
    }

    /** The page is served from the index in the order it sorts, not sorted in memory per request. */
    @Test
    void aPageIsReadFromTheIndexWithoutAnInMemorySort() {
        save("101", 1L, T0, false);
        save("102", 1L, T0.plusSeconds(1), false);

        Document plan = mongoTemplate.getDb().runCommand(Document.parse("""
                {"explain": {"find": "notifications",
                             "filter": {"recipientId": 1},
                             "sort": {"createdAt": -1, "_id": -1},
                             "limit": 2},
                 "verbosity": "queryPlanner"}"""));

        String winningPlan = plan.get("queryPlanner", Document.class).get("winningPlan", Document.class).toJson();
        assertThat(winningPlan).contains("recipient_page_idx").doesNotContain("\"SORT\"");
    }
}
