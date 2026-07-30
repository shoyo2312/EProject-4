package com.tiktok.notificationservice.controller;

import com.tiktok.common.response.ApiResponse;
import com.tiktok.notificationservice.dto.response.NotificationResponse;
import com.tiktok.notificationservice.dto.response.UnreadCountResponse;
import com.tiktok.notificationservice.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping
    public ApiResponse<List<NotificationResponse>> listMine(@AuthenticationPrincipal Long currentUserId) {
        return ApiResponse.success(notificationService.listByUser(currentUserId));
    }

    @GetMapping("/unread-count")
    public ApiResponse<UnreadCountResponse> unreadCount(@AuthenticationPrincipal Long currentUserId) {
        return ApiResponse.success(new UnreadCountResponse(notificationService.unreadCount(currentUserId)));
    }

    @PatchMapping("/{notificationId}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markAsRead(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable String notificationId) {
        notificationService.markAsRead(currentUserId, notificationId);
    }

    @PatchMapping("/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markAllAsRead(@AuthenticationPrincipal Long currentUserId) {
        notificationService.markAllAsRead(currentUserId);
    }
}
