package com.tiktok.authservice.controller;

import com.tiktok.authservice.dto.response.AdminUserResponse;
import com.tiktok.authservice.entity.UserStatus;
import com.tiktok.authservice.service.AdminUserDirectory;
import com.tiktok.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
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
public class AdminUserController {

    private final AdminUserDirectory adminUserDirectory;

    @GetMapping
    public ApiResponse<Page<AdminUserResponse>> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) UserStatus status,
            @PageableDefault(size = 25, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ApiResponse.success(adminUserDirectory.search(q, status, pageable));
    }
}
