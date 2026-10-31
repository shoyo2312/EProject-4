package com.tiktok.chatservice.service;

import com.tiktok.chatservice.entity.ChatImage;
import com.tiktok.chatservice.entity.Conversation;
import com.tiktok.chatservice.entity.Message;
import com.tiktok.chatservice.entity.MessageType;
import com.tiktok.chatservice.repository.ChatImageRepository;
import com.tiktok.chatservice.repository.ConversationRepository;
import com.tiktok.chatservice.repository.MessageRepository;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.mongodb.test.autoconfigure.DataMongoTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@DataMongoTest(properties = "spring.data.mongodb.auto-index-creation=true")
@Testcontainers
class ChatImageMongoTest {
    @Container @ServiceConnection
    static MongoDBContainer MONGO = new MongoDBContainer("mongo:7");
    @Autowired private ChatImageRepository images;
    @Autowired private MessageRepository messages;
    @Autowired private ConversationRepository conversations;
    @Autowired private MongoTemplate mongo;

    @BeforeEach
    void cleanup() {
        images.deleteAll();
        messages.deleteAll();
        conversations.deleteAll();
    }

    private ChatImage upload(String id) {
        return images.save(ChatImage.builder().id(id).conversationId("c").uploaderId(1L).mime("image/png")
                .width(2).height(3).size(4).data(new byte[]{1, 2, 3, 4}).createdAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600)).build());
    }

    @Test
    void onlyTheUploaderInTheSameConversationCanClaimAndOnlyOnce() {
        upload("i1");
        assertThat(images.claim("other", "i1", 1L, "m1")).isFalse();
        assertThat(images.claim("c", "i1", 2L, "m1")).isFalse();
        assertThat(images.claim("c", "i1", 1L, "m1")).isTrue();
        assertThat(images.claim("c", "i1", 1L, "m2")).isFalse();
        ChatImage claimed = images.findInConversation("c", "i1").orElseThrow();
        assertThat(claimed.getMessageId()).isEqualTo("m1");
        assertThat(claimed.getExpiresAt()).isNull();
    }

    @Test
    void twoSendsRacingForOneUploadHaveExactlyOneWinner() throws Exception {
        upload("i1");
        var pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Boolean>> tries = new ArrayList<>();
            for (int n = 0; n < 8; n++) {
                String message = "m" + n;
                tries.add(() -> images.claim("c", "i1", 1L, message));
            }
            int winners = 0;
            for (Future<Boolean> result : pool.invokeAll(tries)) {
                winners += result.get() ? 1 : 0;
            }
            assertThat(winners).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void releasePutsAClaimedUploadBackUpForExpiry() {
        upload("i1");
        images.claim("c", "i1", 1L, "m1");
        images.release("m1");
        ChatImage back = images.findInConversation("c", "i1").orElseThrow();
        assertThat(back.getMessageId()).isNull();
        assertThat(back.getExpiresAt()).isAfter(Instant.now());
    }

    @Test
    void anUnclaimedUploadHasATtlIndexAndAClaimedOneNoExpiry() {
        List<Document> indexes = new ArrayList<>();
        mongo.getCollection("chat_images").listIndexes().into(indexes);
        assertThat(indexes).anySatisfy(index -> {
            assertThat(index.get("key", Document.class)).containsKey("expiresAt");
            assertThat(index.get("expireAfterSeconds")).isEqualTo(0);
        });
        upload("i1");
        images.claim("c", "i1", 1L, "m1");
        Document stored = mongo.getCollection("chat_images").find(new Document("_id", "i1")).first();
        assertThat(stored).doesNotContainKey("expiresAt");
    }

    @Test
    void metadataReadsLeaveTheBytesBehind() {
        upload("i1");
        ChatImage meta = images.findMeta("c", List.of("i1")).get(0);
        assertThat(meta.getData()).isNull();
        assertThat(meta.getWidth()).isEqualTo(2);
        assertThat(images.findMeta("other", List.of("i1"))).isEmpty();
    }

    @Test
    void deletingByMessageRemovesTheBytes() {
        upload("i1");
        images.claim("c", "i1", 1L, "m1");
        images.deleteByMessageId("m1");
        assertThat(images.findInConversation("c", "i1")).isEmpty();
    }

    @Test
    void recallRemovesTheImageIdAndTheCaptionFromTheStoredMessageAndThePreviewHasNoCaption() {
        conversations.save(Conversation.builder().id("c").participantIds(List.of(1L, 2L)).participantKey("1_2").build());
        Message message = messages.save(Message.builder().id("m1").conversationId("c").senderId(1L)
                .type(MessageType.IMAGE).imageId("i1").content("secret").sentAt(Instant.now()).build());
        conversations.recordMessage("c", 1L, MessageType.IMAGE, "secret", message.getSentAt());
        assertThat(conversations.findById("c").orElseThrow().getLastMessageContent()).isNull();
        assertThat(conversations.findById("c").orElseThrow().getLastMessageType()).isEqualTo(MessageType.IMAGE);

        assertThat(messages.recall("c", "m1", 1L, Instant.now())).isTrue();
        Document stored = mongo.getCollection("messages").find(new Document("_id", "m1")).first();
        assertThat(stored).doesNotContainKeys("imageId", "content");
        Optional<Message> reread = messages.findById("m1");
        assertThat(reread).isPresent();
        assertThat(reread.get().getType()).isEqualTo(MessageType.IMAGE);
        assertThat(reread.get().getImageId()).isNull();
    }
}
