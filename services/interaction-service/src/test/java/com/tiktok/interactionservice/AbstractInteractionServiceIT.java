package com.tiktok.interactionservice;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.CassandraContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Shared Cassandra + Redis Testcontainers fixture. Subclasses share the same static
 * containers (started once per JVM fork) instead of paying the ~30-60s Cassandra boot cost
 * per test class. Kafka is mocked — nothing under test asserts on actual message delivery.
 *
 * <p>{@code @DirtiesContext} forces a fresh Spring context (and CqlSession) per test class
 * instead of reusing the cached one: a reused session left idle between test classes was
 * observed going stale against the DataStax driver 4.x + Testcontainers Cassandra combo,
 * causing {@code NoNodeAvailableException} on the next query. The container itself stays
 * up and is still shared — only the client-side session is recreated.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class AbstractInteractionServiceIT {

    @Container
    @ServiceConnection
    static final CassandraContainer<?> CASSANDRA = new CassandraContainer<>("cassandra:5.0")
            .withInitScript("cassandra-init-test.cql");

    @Container
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @MockBean
    protected KafkaTemplate<String, String> kafkaTemplate;

    /**
     * A mock send answers null by default, and the publisher now waits on the future it gets back
     * — so without this every counter-moving path would fail on a NullPointerException that says
     * nothing about the code under test. An already-completed future is the broker acking at once.
     */
    @BeforeEach
    void ackEveryPublish() {
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
    }
}
