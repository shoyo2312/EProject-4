package com.tiktok.interactionservice.controller;

import com.tiktok.common.response.ApiResponse;
import com.tiktok.interactionservice.dto.request.AdminCommentFilter;
import com.tiktok.interactionservice.dto.response.AdminCommentPageResponse;
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
 *
 * <p>Two endpoints rather than one flat list, mirroring {@link CommentController}: the top-level
 * page stays small however long a single thread runs, and a thread is fetched only when an admin
 * opens it.
 */
@RestController
@RequestMapping("/api/v1/interactions/admin")
@RequiredArgsConstructor
public class AdminCommentController {

    /** Same ceiling CommentController applies, and for the same reason: size feeds the driver's fetch size. */
    private static final int MAX_PAGE_SIZE = 100;

    private final AdminCommentDirectory adminCommentDirectory;

    /**
     * A video's comments, newest first. Removed ones are included in every filter — see
     * {@link AdminCommentDirectory}. The default is the thread view: top-level comments only, with
     * replies behind the endpoint below.
     */
    @GetMapping("/videos/{videoId}/comments")
    public ApiResponse<AdminCommentPageResponse> listComments(
            @PathVariable Long videoId,
            @RequestParam(defaultValue = "THREAD") AdminCommentFilter filter,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.success(adminCommentDirectory.listForAdmin(
                videoId, filter, cursor, Math.clamp(size, 1, MAX_PAGE_SIZE)));
    }

    /**
     * Specific comments by id — the parent a reply answers, for the context line above it. Capped
     * at one page's worth: this is a lookup for what is already on screen, not a second listing.
     */
    @GetMapping("/videos/{videoId}/comments/by-ids")
    public ApiResponse<List<AdminCommentResponse>> listByIds(
            @PathVariable Long videoId,
            @RequestParam List<Long> ids) {
        return ApiResponse.success(adminCommentDirectory.listByIds(
                videoId, ids.stream().distinct().limit(MAX_PAGE_SIZE).toList()));
    }

    /** One comment's replies, oldest first. */
    @GetMapping("/videos/{videoId}/comments/{commentId}/replies")
    public ApiResponse<AdminCommentPageResponse> listReplies(
            @PathVariable Long videoId,
            @PathVariable Long commentId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(adminCommentDirectory.listRepliesForAdmin(
                videoId, commentId, cursor, Math.clamp(size, 1, MAX_PAGE_SIZE)));
    }
}
