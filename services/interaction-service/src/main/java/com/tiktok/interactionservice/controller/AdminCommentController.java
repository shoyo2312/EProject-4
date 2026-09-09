package com.tiktok.interactionservice.controller;

import com.tiktok.common.response.ApiResponse;
import com.tiktok.interactionservice.dto.response.AdminCommentResponse;
import com.tiktok.interactionservice.service.AdminCommentDirectory;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read side for the moderation console. Removal is not here — it goes through admin-service, so
 * that it lands in the audit log and reaches this service as a moderation event like every other
 * decision.
 */
@RestController
@RequestMapping("/api/v1/interactions/admin")
@RequiredArgsConstructor
public class AdminCommentController {

    /** Same ceiling CommentController applies, and for the same reason: size feeds the driver's fetch size. */
    private static final int MAX_PAGE_SIZE = 100;

    private final AdminCommentDirectory adminCommentDirectory;

    @GetMapping("/videos/{videoId}/comments")
    public ApiResponse<List<AdminCommentResponse>> listComments(
            @PathVariable Long videoId,
            @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.success(
                adminCommentDirectory.listForAdmin(videoId, Math.clamp(size, 1, MAX_PAGE_SIZE)));
    }
}
