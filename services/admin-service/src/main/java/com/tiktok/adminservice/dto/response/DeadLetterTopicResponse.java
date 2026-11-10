package com.tiktok.adminservice.dto.response;

import java.time.Instant;

/**
 * One {@code <topic>.DLT}.
 *
 * @param retained records the broker still holds — retention removes them, a replay does not
 * @param pending  of those, the ones no replay of every pending record has been past yet
 * @param latestAt when the newest was parked; null when the broker holds none
 */
public record DeadLetterTopicResponse(String topic, String originTopic, long retained, long pending, Instant latestAt) {
}
