package com.tiktok.videoservice.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * tryClaim's whole guarantee rests on the unique index on eventId. Spring Data Mongo does not
 * create @Indexed indexes unless spring.data.mongodb.auto-index-creation is on, and a missing
 * index degrades tryClaim to "always claims" with no error anywhere — so the index itself is
 * asserted here rather than assumed.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class ProcessedEventRepositoryTest {

    @Container
    @ServiceConnection
    static MongoDBContainer MONGO = new MongoDBContainer("mongo:7");

    @MockBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private MongoTemplate mongoTemplate;

    @BeforeEach
    void cleanUp() {
        processedEventRepository.deleteAll();
    }

    @Test
    void eventIdHasUniqueIndex() {
        boolean unique = mongoTemplate.indexOps("processed_events").getIndexInfo().stream()
                .anyMatch(index -> index.isUnique()
                        && index.getIndexFields().stream()
                        .anyMatch(field -> field.getKey().equals("eventId")));

        assertThat(unique)
                .as("unique index on processed_events.eventId — tryClaim is a no-op without it")
                .isTrue();
    }

    @Test
    void tryClaim_firstCallWins_secondIsRejected() {
        assertThat(processedEventRepository.tryClaim("evt-1", "VideoLikeEvent")).isTrue();
        assertThat(processedEventRepository.tryClaim("evt-1", "VideoLikeEvent")).isFalse();
        assertThat(processedEventRepository.count()).isEqualTo(1);
    }

    @Test
    void releaseClaim_allowsReclaiming() {
        processedEventRepository.tryClaim("evt-2", "VideoLikeEvent");

        processedEventRepository.releaseClaim("evt-2");

        assertThat(processedEventRepository.tryClaim("evt-2", "VideoLikeEvent")).isTrue();
    }
}
