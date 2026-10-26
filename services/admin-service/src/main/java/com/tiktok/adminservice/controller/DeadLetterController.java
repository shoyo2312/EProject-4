package com.tiktok.adminservice.controller;

import com.tiktok.adminservice.dto.request.ReplayDeadLetterRequest;
import com.tiktok.adminservice.dto.response.DeadLetterBatchReplayResponse;
import com.tiktok.adminservice.dto.response.DeadLetterRecordsResponse;
import com.tiktok.adminservice.dto.response.DeadLetterReplayResponse;
import com.tiktok.adminservice.dto.response.DeadLetterTopicResponse;
import com.tiktok.adminservice.service.DeadLetterService;
import com.tiktok.common.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Kafka's dead-letter topics, for the console's Queues &amp; DLQ screen. {@code {topic}} is the
 * DLT's full name, {@code video.video-events.DLT}.
 */
@Validated
@RestController
@RequestMapping("/api/v1/admin/system/dead-letters")
@RequiredArgsConstructor
public class DeadLetterController {

    private final DeadLetterService deadLetterService;

    @GetMapping
    public ApiResponse<List<DeadLetterTopicResponse>> listTopics() {
        return ApiResponse.success(deadLetterService.listTopics());
    }

    @GetMapping("/{topic}")
    public ApiResponse<DeadLetterRecordsResponse> listRecords(
            @PathVariable String topic,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        return ApiResponse.success(deadLetterService.listRecords(topic, limit));
    }

    @PostMapping("/{topic}/replay")
    public ApiResponse<DeadLetterReplayResponse> replay(
            @AuthenticationPrincipal Long currentAdminId,
            @PathVariable String topic,
            @Valid @RequestBody ReplayDeadLetterRequest request) {
        return ApiResponse.success(deadLetterService.replay(currentAdminId, topic,
                request.partition(), request.offset(), request.force()));
    }

    @PostMapping("/{topic}/replay-pending")
    public ApiResponse<DeadLetterBatchReplayResponse> replayPending(
            @AuthenticationPrincipal Long currentAdminId,
            @PathVariable String topic) {
        return ApiResponse.success(deadLetterService.replayPending(currentAdminId, topic));
    }
}
