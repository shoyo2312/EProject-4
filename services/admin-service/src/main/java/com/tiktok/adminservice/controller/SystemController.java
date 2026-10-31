package com.tiktok.adminservice.controller;

import com.tiktok.adminservice.dto.response.OutboxBacklogResponse;
import com.tiktok.adminservice.service.SystemStatusService;
import com.tiktok.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** The console's Queues &amp; DLQ screen, besides the dead letters ({@link DeadLetterController}). */
@RestController
@RequestMapping("/api/v1/admin/system")
@RequiredArgsConstructor
public class SystemController {

    private final SystemStatusService systemStatusService;

    @GetMapping("/outboxes")
    public ApiResponse<List<OutboxBacklogResponse>> outboxes() {
        return ApiResponse.success(systemStatusService.outboxes());
    }
}
