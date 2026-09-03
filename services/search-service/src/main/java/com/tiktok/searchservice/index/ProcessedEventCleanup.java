package com.tiktok.searchservice.index;

import com.tiktok.searchservice.document.ProcessedEventDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.query.Criteria;
import org.springframework.data.elasticsearch.core.query.CriteriaQuery;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * The inbox only grows: one document per event this service has ever consumed, and nothing was
 * ever removing them. Mongo services solve it with a TTL index and PostgreSQL ones with a
 * retention job — Elasticsearch has neither, so this is the job.
 *
 * <p>A row is only deletable once redelivery of that event has become impossible. Kafka retention
 * is the bound that matters, and the default is seven days; the window here is deliberately
 * wider so a replay inside Kafka's retention still finds its claim.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProcessedEventCleanup {

    private final ElasticsearchOperations elasticsearchOperations;

    @Value("${search.processed-events.retention-days:30}")
    private long retentionDays;

    @Scheduled(cron = "${search.processed-events.cleanup-cron:0 30 3 * * *}")
    public void deleteExpired() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(retentionDays));
        CriteriaQuery expired = new CriteriaQuery(Criteria.where("processedAt").lessThan(cutoff));

        long deleted = elasticsearchOperations
                .delete(expired, ProcessedEventDocument.class)
                .getDeleted();

        log.info("Deleted {} processed_events older than {}", deleted, cutoff);
    }
}
