package com.tiktok.userservice.controller;

import com.tiktok.common.response.ApiResponse;
import com.tiktok.userservice.dto.request.ScreenTimeHeartbeatRequest;
import com.tiktok.userservice.dto.response.ScreenTimeWeekResponse;
import com.tiktok.userservice.service.ScreenTimeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1/users/me/screen-time")
@RequiredArgsConstructor
@Tag(name = "Screen time", description = "Time on the app, behind Settings → Screen time")
public class ScreenTimeController {

    private final ScreenTimeService screenTimeService;

    @Operation(summary = "Count time on the app",
            description = "Sent by the web about once a minute while the tab is visible and focused. "
                    + "seconds is clamped to 120; timeZone is the browser's IANA zone and is stored on the "
                    + "account. 400 VALIDATION_ERROR for an unknown zone.")
    @PostMapping("/heartbeat")
    public ApiResponse<Void> heartbeat(@AuthenticationPrincipal Long currentUserId,
                                       @Valid @RequestBody ScreenTimeHeartbeatRequest request) {
        screenTimeService.recordHeartbeat(currentUserId, request.seconds(), request.timeZone(), Instant.now());
        return ApiResponse.success(null);
    }

    @Operation(summary = "One week of time on the app",
            description = "Seven days from weekStart (a Monday, YYYY-MM-DD), Monday first and zero-filled, "
                    + "each split into day time (06:00–22:00) and night time. 400 VALIDATION_ERROR for any "
                    + "other day.")
    @GetMapping
    public ApiResponse<ScreenTimeWeekResponse> week(
            @AuthenticationPrincipal Long currentUserId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekStart) {
        return ApiResponse.success(screenTimeService.getWeek(currentUserId, weekStart));
    }
}
