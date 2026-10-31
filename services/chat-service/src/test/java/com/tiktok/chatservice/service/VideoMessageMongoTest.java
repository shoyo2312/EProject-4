package com.tiktok.chatservice.service;

import com.tiktok.chatservice.entity.*;
import com.tiktok.chatservice.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.mongodb.test.autoconfigure.DataMongoTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

@DataMongoTest(properties = "spring.data.mongodb.auto-index-creation=true")
@Testcontainers
class VideoMessageMongoTest {
    @Container @ServiceConnection
    static MongoDBContainer MONGO = new MongoDBContainer("mongo:7");
    @Autowired private MessageRepository messages;
    @Autowired private ConversationRepository conversations;
    @Autowired private MongoTemplate mongo;

    @BeforeEach
    void cleanup() {
        messages.deleteAll();
        conversations.deleteAll();
    }

    private Message message(String id, String key, Long sender) {
        return Message.builder().id(id).conversationId("c").senderId(sender).type(MessageType.VIDEO)
                .videoId("123").content("secret caption").clientRequestId(key).sentAt(Instant.now()).build();
    }

    @Test
    void partialUniqueIndexIncludesOnlyDocumentsWithRequestIdAndIsScopedToSender() {
        messages.save(message("1", null, 1L));
        messages.save(message("2", null, 1L));
        messages.save(message("3", "key", 1L));
        messages.save(message("4", "key", 2L));
        assertThatThrownBy(() -> messages.save(message("5", "key", 1L))).isInstanceOf(DuplicateKeyException.class);
        assertThat(messages.count()).isEqualTo(4);
        assertThat(messages.findByConversationIdAndSenderIdAndClientRequestId("c", 1L, "key")).isPresent();
    }

    @Test
    void captionNeverEntersPreviewAndRecallRemovesStoredVideoId() {
        conversations.save(Conversation.builder().id("c").participantIds(List.of(1L, 2L)).participantKey("1_2").build());
        Message message = messages.save(message("1", "key", 1L));
        conversations.recordMessage("c", 1L, MessageType.VIDEO, message.getContent(), message.getSentAt());
        var preview = conversations.findById("c").orElseThrow();
        assertThat(preview.getLastMessageType()).isEqualTo(MessageType.VIDEO);
        assertThat(preview.getLastMessageContent()).isNull();
        assertThat(messages.recall("c", "1", 1L, Instant.now())).isTrue();
        var recalled = messages.findById("1").orElseThrow();
        assertThat(recalled.getVideoId()).isNull();
        assertThat(recalled.getContent()).isNull();
        assertThat(recalled.getClientRequestId()).isEqualTo("key");
        var document = mongo.getCollection("messages").find(new org.bson.Document("_id", "1")).first();
        assertThat(document).doesNotContainKeys("videoId", "content");
    }
}
