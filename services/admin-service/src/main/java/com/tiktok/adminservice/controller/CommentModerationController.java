package com.tiktok.adminservice.controller;

import com.tiktok.adminservice.dto.request.ModerationRequest;
import com.tiktok.adminservice.dto.response.ModerationActionResponse;
import com.tiktok.adminservice.entity.CommentTarget;
import com.tiktok.adminservice.entity.ModerationActionType;
import com.tiktok.adminservice.entity.ReportTargetType;
import com.tiktok.adminservice.service.AdminService;
import com.tiktok.common.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Removing a comment on a user's behalf. Both ids are in the path because that pair is what
 * identifies a comment — see {@link CommentTarget}.
 *
 * <p>There is no restore counterpart. A comment's soft delete is undone only by the compensation
 * inside interaction-service, which is conditioned on the exact deletion it wrote; a second,
 * unconditional un-delete would resurrect comments other deletions removed.
 */
@RestController
@RequestMapping("/api/v1/admin/comments")
@RequiredArgsConstructor
public class CommentModerationController {

    private final AdminService adminService;

    @PostMapping("/{videoId}/{commentId}/remove")
    public ApiResponse<ModerationActionResponse> remove(
            @AuthenticationPrincipal Long currentAdminId,
            @PathVariable Long videoId,
            @PathVariable Long commentId,
            @Valid @RequestBody ModerationRequest request) {
        return ApiResponse.success(adminService.moderate(currentAdminId, ReportTargetType.COMMENT,
                new CommentTarget(videoId, commentId).targetId(),
                ModerationActionType.REMOVE_COMMENT, request.reason()));
    }
}
