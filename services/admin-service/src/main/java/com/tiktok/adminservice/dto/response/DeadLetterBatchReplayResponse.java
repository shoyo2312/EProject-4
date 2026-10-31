package com.tiktok.adminservice.dto.response;

import java.util.List;

/**
 * @param results      one per record handled, in the order they were
 * @param stillPending what is left for the next call: the batch has a cap, and a call that runs
 *                     out of time stops between records
 */
public record DeadLetterBatchReplayResponse(List<DeadLetterReplayResponse> results, long stillPending) {
}
