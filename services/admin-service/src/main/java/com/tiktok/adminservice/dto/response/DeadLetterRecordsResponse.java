package com.tiktok.adminservice.dto.response;

import java.util.List;

/** @param records the newest ones, newest first */
public record DeadLetterRecordsResponse(DeadLetterTopicResponse topic, List<DeadLetterRecordResponse> records) {
}
