package com.tiktok.adminservice.service;

import com.tiktok.adminservice.dto.response.DeadLetterBatchReplayResponse;
import com.tiktok.adminservice.dto.response.DeadLetterRecordsResponse;
import com.tiktok.adminservice.dto.response.DeadLetterReplayResponse;
import com.tiktok.adminservice.dto.response.DeadLetterTopicResponse;
import com.tiktok.adminservice.entity.DeadLetterReplay;
import com.tiktok.adminservice.exception.DeadLetterNotFoundException;
import com.tiktok.adminservice.exception.DeadLetterTopicNotFoundException;
import com.tiktok.adminservice.exception.KafkaUnavailableException;
import com.tiktok.adminservice.exception.NotADeadLetterTopicException;
import com.tiktok.adminservice.mapper.DeadLetterMapper;
import com.tiktok.adminservice.repository.DeadLetterReplayRepository;
import com.tiktok.kafka.dlt.DeadLetter;
import com.tiktok.kafka.dlt.DeadLetters;
import com.tiktok.kafka.dlt.ReplayResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeadLetterServiceImpl implements DeadLetterService {

    /** Each record replayed reads its original partition to the end, so one call is kept to this many. */
    static final int REPLAY_BATCH = 50;
    private static final int DETAIL_LENGTH = 500;

    private final DeadLetters deadLetters;
    private final DeadLetterReplayRepository replayRepository;
    private final DeadLetterMapper mapper;

    @Override
    public List<DeadLetterTopicResponse> listTopics() {
        return kafka(null, deadLetters::topics).stream().map(mapper::toResponse).toList();
    }

    @Override
    public DeadLetterRecordsResponse listRecords(String topic, int limit) {
        requireDeadLetterTopic(topic);
        DeadLetterTopicResponse described = mapper.toResponse(kafka(topic, () -> deadLetters.topic(topic)));
        List<DeadLetter> letters = kafka(topic, () -> deadLetters.latest(topic, limit));
        Map<RecordKey, DeadLetterReplay> lastReplays = lastReplays(topic, letters);
        return new DeadLetterRecordsResponse(described, letters.stream()
                .map(letter -> mapper.toResponse(letter, lastReplays.get(
                        new RecordKey(letter.partition(), letter.offset(), letter.parkedAt()))))
                .toList());
    }

    @Override
    public DeadLetterReplayResponse replay(Long adminId, String topic, int partition, long offset, boolean force) {
        requireDeadLetterTopic(topic);
        ReplayResult result = kafka(topic, () -> deadLetters.replay(topic, partition, offset, force));
        if (result.outcome() == ReplayResult.Outcome.NOT_FOUND) {
            throw new DeadLetterNotFoundException(result.coordinates());
        }
        record(adminId, result, force);
        return mapper.toResponse(result);
    }

    /**
     * Each record is recorded as soon as it is handled, so a broker that stops answering midway
     * leaves the rows for what was done. The dlt-replay group has moved past those too, and a
     * second call carries on after them.
     */
    @Override
    public DeadLetterBatchReplayResponse replayPending(Long adminId, String topic) {
        requireDeadLetterTopic(topic);
        List<DeadLetterReplayResponse> results = new ArrayList<>();
        kafka(topic, () -> deadLetters.replayPending(topic, false, REPLAY_BATCH, result -> {
            record(adminId, result, false);
            results.add(mapper.toResponse(result));
        }));
        long stillPending = kafka(topic, () -> deadLetters.topic(topic)).pending();
        return new DeadLetterBatchReplayResponse(results, stillPending);
    }

    private void record(Long adminId, ReplayResult result, boolean force) {
        log.info("Admin {} replayed {}{}: {} {}", adminId, result.coordinates(), force ? " (forced)" : "",
                result.outcome(), result.detail() == null ? "" : result.detail());
        String detail = result.detail();
        replayRepository.save(DeadLetterReplay.builder()
                .adminId(adminId)
                .dltTopic(result.topic())
                .dltPartition(result.partition())
                .dltOffset(result.offset())
                .parkedAt(result.parkedAt())
                .outcome(result.outcome())
                .detail(detail != null && detail.length() > DETAIL_LENGTH ? detail.substring(0, DETAIL_LENGTH) : detail)
                .forced(force)
                .build());
    }

    private Map<RecordKey, DeadLetterReplay> lastReplays(String topic, List<DeadLetter> letters) {
        if (letters.isEmpty()) {
            return Map.of();
        }
        Map<RecordKey, DeadLetterReplay> latest = new HashMap<>();
        for (DeadLetterReplay replay : replayRepository.findByDltTopicAndDltOffsetIn(
                topic, letters.stream().map(DeadLetter::offset).distinct().toList())) {
            latest.merge(new RecordKey(replay.getDltPartition(), replay.getDltOffset(), replay.getParkedAt()), replay,
                    (a, b) -> a.getId() > b.getId() ? a : b);
        }
        return latest;
    }

    private static void requireDeadLetterTopic(String topic) {
        if (!DeadLetters.isDeadLetterTopic(topic)) {
            throw new NotADeadLetterTopicException(topic);
        }
    }

    private static <T> T kafka(String topic, Supplier<T> call) {
        try {
            return call.get();
        } catch (UnknownTopicOrPartitionException e) {
            throw new DeadLetterTopicNotFoundException(topic);
        } catch (KafkaException e) {
            log.warn("Kafka did not answer for {}: {}", topic == null ? "the dead-letter topics" : topic, e.toString());
            throw new KafkaUnavailableException(e);
        }
    }

    private record RecordKey(int partition, long offset, Instant parkedAt) {
    }
}
