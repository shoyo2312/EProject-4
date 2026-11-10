package com.tiktok.adminservice.dto.response;

import com.tiktok.kafka.dlt.ReplayResult;

/** @param detail where it went, what held it back, or where the earlier replay is */
public record DeadLetterReplayResponse(int partition, long offset, ReplayResult.Outcome outcome, String detail) {
}
