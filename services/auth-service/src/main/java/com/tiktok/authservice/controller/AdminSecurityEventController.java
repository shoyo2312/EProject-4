package com.tiktok.authservice.controller;

import com.tiktok.authservice.dto.response.SecurityEventResponse;
import com.tiktok.authservice.entity.AuthSecurityEvent;
import com.tiktok.authservice.repository.AuthSecurityEventRepository;
import com.tiktok.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * One account's security log, for the console's activity timeline.
 *
 * <p>Under {@code /api/v1/auth/admin/**} like the account directory, so it inherits the
 * ROLE_ADMIN rule already in {@code SecurityConfig} and the gateway route auth-service already
 * has. This is the whole platform's history, not the caller's own — authenticated is not enough.
 *
 * <p>No service layer between this and the repository on purpose: there is no decision to make
 * here. It reads one account's rows in the one order they are ever read and maps them to a
 * record. An interface and an impl around that would be three files saying "select".
 */
@RestController
@RequestMapping("/api/v1/auth/admin/users/{userId}/security-events")
@RequiredArgsConstructor
@Tag(name = "Admin users", description = "The console's account directory — ROLE_ADMIN only")
public class AdminSecurityEventController {

    private final AuthSecurityEventRepository repository;

    @GetMapping
    @Operation(summary = "One account's security log",
            description = "Sign-ins and failed attempts, password resets, address changes and the "
                    + "admin second factor, newest first. Token refreshes are not recorded, and "
                    + "bans are not either — those are in admin-service's audit log with the "
                    + "admin and the reason that caused them.")
    public ApiResponse<Page<SecurityEventResponse>> list(
            @PathVariable Long userId,
            @PageableDefault(size = 25) Pageable pageable) {
        return ApiResponse.success(repository.findByUserIdOrderByCreatedAtDesc(userId, pageable)
                .map(AdminSecurityEventController::toResponse));
    }

    private static SecurityEventResponse toResponse(AuthSecurityEvent event) {
        return new SecurityEventResponse(
                event.getId(),
                event.getEventType(),
                event.getIpAddress(),
                event.getUserAgent(),
                event.getDeviceLabel(),
                event.getDetail(),
                event.getCreatedAt());
    }
}
