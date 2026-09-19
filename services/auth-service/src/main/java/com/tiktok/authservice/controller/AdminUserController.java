package com.tiktok.authservice.controller;

import com.tiktok.authservice.dto.response.AdminUserResponse;
import com.tiktok.authservice.entity.UserStatus;
import com.tiktok.authservice.service.AdminUserDirectory;
import com.tiktok.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sits under /api/v1/auth/** so it reaches the console through the gateway route auth-service
 * already has. Banning is not here — that is a moderation decision and belongs to admin-service,
 * which records it in the audit log and emits the event this service consumes back.
 */
@RestController
@RequestMapping("/api/v1/auth/admin/users")
@RequiredArgsConstructor
@Validated
@Tag(name = "Admin users", description = "The console's account directory — ROLE_ADMIN only")
public class AdminUserController {

    /**
     * The term goes into a {@code like '%…%'}, which no index shortens. Long enough to matter is
     * long enough to be a mistake or an attempt to make one query cost a table scan, and a
     * handle or a display name fits in far less.
     */
    private static final int MAX_QUERY_LENGTH = 100;

    private final AdminUserDirectory adminUserDirectory;

    @GetMapping
    @Operation(summary = "Search the platform's accounts",
            description = "Matches the handle or email, optionally narrowed to one status. "
                    + "A blank q lists everyone, newest first.")
    public ApiResponse<Page<AdminUserResponse>> list(
            @RequestParam(required = false) @Size(max = MAX_QUERY_LENGTH) String q,
            @RequestParam(required = false) UserStatus status,
            @PageableDefault(size = 25, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ApiResponse.success(adminUserDirectory.search(q, status, pageable));
    }
}
