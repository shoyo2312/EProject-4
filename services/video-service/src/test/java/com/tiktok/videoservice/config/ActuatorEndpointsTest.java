package com.tiktok.videoservice.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What a container probe and Prometheus need answers without a token — every other request here
 * needs one — and nothing else under /actuator does. Asserted on one service; the rule is
 * security-lib's, shared by every service that uses it.
 */
@SpringBootTest
@AutoConfigureMockMvc
// A test context switches metric export off unless told otherwise, and the scrape with it.
@AutoConfigureMetrics
@Testcontainers
@ActiveProfiles("test")
class ActuatorEndpointsTest {

    @Container
    @ServiceConnection
    static MongoDBContainer MONGO = new MongoDBContainer("mongo:7");

    @MockitoBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void theProbeAndTheScrape_answerWithoutAToken() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("application=\"video-service\"")))
                // The name an alert rule is written against: kafka-lib's gauge, bound from a bean.
                .andExpect(content().string(containsString("outbox_oldest_pending_age_seconds{")))
                .andExpect(content().string(containsString("outbox=\"published\"")));
    }

    @Test
    void anythingElseUnderActuator_isRefused() throws Exception {
        mockMvc.perform(get("/actuator/env"))
                .andExpect(status().isForbidden());
    }
}
