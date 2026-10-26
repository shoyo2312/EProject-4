package com.tiktok.adminservice.dto.response;

import java.util.List;

/**
 * One service's outboxes, as kafka-lib's OutboxBacklog gauges report them.
 *
 * @param reachable false when its actuator did not answer; {@code outboxes} is then empty
 */
public record OutboxBacklogResponse(String service, boolean reachable, List<Outbox> outboxes) {

    /**
     * @param pending                 rows still waiting for a broker ack; null when the service could
     *                                not count them — its own database did not answer
     * @param oldestPendingAgeSeconds how long the oldest of them has been due, 0 when there is none;
     *                                the one to read, since a count cannot tell busy from stuck
     */
    public record Outbox(String name, Long pending, Double oldestPendingAgeSeconds) {
    }
}
