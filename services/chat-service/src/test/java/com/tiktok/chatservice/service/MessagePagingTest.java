package com.tiktok.chatservice.service;

import com.tiktok.chatservice.client.BlockClient;
import com.tiktok.chatservice.dto.response.MessagePageResponse;
import com.tiktok.chatservice.dto.response.MessageResponse;
import com.tiktok.chatservice.entity.Message;
import com.tiktok.chatservice.mapper.MessageMapperImpl;
import com.tiktok.chatservice.repository.ConversationRepository;
import com.tiktok.chatservice.repository.MessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Mongo stores sentAt to the millisecond, so several messages inside one is ordinary: a burst from
 * one client, or both people typing at once. Paged on sentAt alone, the ones sharing a millisecond
 * with a page's last message were cut from the history. Against a real Mongo, because what went
 * wrong was how Mongo compares and orders the rows, not anything the service computed.
 */
@DataMongoTest
@Testcontainers
class MessagePagingTest {

    @Container
    @ServiceConnection
    static MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse("mongo:7"));

    private static final Instant BURST = Instant.parse("2026-10-07T10:00:00.123Z");

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private ConversationRepository conversationRepository;

    private MessageServiceImpl messageService;

    @BeforeEach
    void setUp() {
        messageRepository.deleteAll();
        messageService = new MessageServiceImpl(mock(ConversationService.class), conversationRepository,
                messageRepository, new MessageMapperImpl(), mock(SimpMessagingTemplate.class), mock(BlockClient.class));

        save("100", BURST.minusMillis(1));
        for (int i = 1; i <= 5; i++) {
            save("20" + i, BURST);
        }
        save("300", BURST.plusMillis(1));
        save("400", BURST.minusMillis(2), Instant.now());
    }

    @Test
    void pagingThroughABurstInOneMillisecond_returnsEveryMessageOnce() {
        // Two per page, so page boundaries land inside the burst more than once.
        assertThat(readWholeHistory(null, 2))
                .containsExactly("300", "205", "204", "203", "202", "201", "100");
    }

    @Test
    void aCursorIssuedBeforeTheDeploy_stillPagesStrictlyOlder() {
        String legacy = Base64.getEncoder().encodeToString(
                BURST.toString().getBytes(StandardCharsets.UTF_8));

        // It never carried an id, so it pages the way it always did; from then on the cursors it
        // is handed back are the new kind.
        assertThat(readWholeHistory(legacy, 2)).containsExactly("100");
    }

    private List<String> readWholeHistory(String cursor, int size) {
        List<String> seen = new ArrayList<>();
        do {
            MessagePageResponse page = messageService.listMessages("c1", 1L, cursor, size);
            page.items().stream().map(MessageResponse::id).forEach(seen::add);
            cursor = page.nextCursor();
        } while (cursor != null);
        return seen;
    }

    private void save(String id, Instant sentAt) {
        save(id, sentAt, null);
    }

    private void save(String id, Instant sentAt, Instant deletedAt) {
        messageRepository.save(Message.builder()
                .id(id).conversationId("c1").senderId(1L).content("m" + id).sentAt(sentAt).deletedAt(deletedAt)
                .build());
    }
}
