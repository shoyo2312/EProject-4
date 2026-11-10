package com.tiktok.adminservice.service;

import com.tiktok.adminservice.dto.response.DeadLetterBatchReplayResponse;
import com.tiktok.adminservice.dto.response.DeadLetterRecordsResponse;
import com.tiktok.adminservice.dto.response.DeadLetterReplayResponse;
import com.tiktok.adminservice.dto.response.DeadLetterTopicResponse;

import java.util.List;

/**
 * The console's view of kafka-lib's dead-letter topics, and the replay that {@code make dlt-replay}
 * does by hand — see {@link com.tiktok.kafka.dlt.DeadLetters} for the order check both rely on.
 * Every replay attempt is recorded, whatever became of it.
 */
public interface DeadLetterService {

    List<DeadLetterTopicResponse> listTopics();

    DeadLetterRecordsResponse listRecords(String topic, int limit);

    DeadLetterReplayResponse replay(Long adminId, String topic, int partition, long offset, boolean force);

    /**
     * The next batch of pending records, never forced: one record whose original partition has a
     * later event under its key needs someone to read what that event is, which a bulk action
     * cannot do. Those are held back and replayed on their own.
     */
    DeadLetterBatchReplayResponse replayPending(Long adminId, String topic);
}
