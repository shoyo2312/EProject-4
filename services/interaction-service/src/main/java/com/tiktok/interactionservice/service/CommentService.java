package com.tiktok.interactionservice.service;

import com.tiktok.interactionservice.dto.response.CommentLikeResponse;
import com.tiktok.interactionservice.dto.response.CommentPermissionResponse;
import com.tiktok.interactionservice.dto.response.CommentPageResponse;
import com.tiktok.interactionservice.dto.response.CommentResponse;

public interface CommentService {

    /**
     * Posts a reply when {@code parentId} is non-null, otherwise a top-level comment. The answer's
     * {@code status} is HELD when the text rules, the owner's own filters or the classifier held it
     * for the video's owner: stored, but listed, counted and announced only once the owner approves
     * it. See {@link CommentScreening}.
     *
     * @throws com.tiktok.interactionservice.exception.CommentingRestrictedException while the caller's
     *         strikes restrict them, checked before anything else
     */
    CommentResponse addComment(Long videoId, Long currentUserId, String content, Long parentId);

    default CommentResponse addComment(Long videoId, Long currentUserId, String content) {
        return addComment(videoId, currentUserId, content, null);
    }

    /**
     * {@code currentUserId} is null for an anonymous caller — the page then comes back with
     * {@code likedByMe} false throughout and no membership lookup is done.
     */
    CommentPageResponse listComments(Long videoId, String cursor, int size, Long currentUserId);

    default CommentPageResponse listComments(Long videoId, String cursor, int size) {
        return listComments(videoId, cursor, size, null);
    }

    /**
     * One top-level comment's replies, oldest first — the order a thread reads in. Paged the same
     * way as {@link #listComments}: an unknown {@code parentId} is an empty page, not an error.
     */
    CommentPageResponse listReplies(Long videoId, Long parentId, String cursor, int size, Long currentUserId);

    default CommentPageResponse listReplies(Long videoId, Long parentId, String cursor, int size) {
        return listReplies(videoId, parentId, cursor, size, null);
    }

    /**
     * Whether {@code currentUserId} may comment on this video, and if not why — read off the same
     * rule {@link #addComment} enforces, so a client can hide the comment box instead of letting
     * the viewer run into the refusal.
     */
    CommentPermissionResponse commentPermission(Long videoId, Long currentUserId);

    void deleteComment(Long videoId, Long commentId, Long currentUserId);

    /**
     * Removes a comment on an admin's decision, with no ownership check — the decision was already
     * made and recorded in admin-service — and counts a strike against its author. A no-op when the
     * comment is unknown or already gone, so that a redelivered moderation event is harmless.
     */
    void removeByAdmin(Long videoId, Long commentId);

    /**
     * The comments the text rules held on a video, newest first, for its owner to approve or delete.
     * Replies whose thread was deleted in the meantime are left out: approving one would count a
     * reply nobody can see.
     *
     * @throws com.tiktok.interactionservice.exception.NotVideoOwnerException for anyone but the
     *         owner — and for the owner while video-service cannot confirm it (fails closed)
     */
    CommentPageResponse listHeld(Long videoId, String cursor, int size, Long currentUserId);

    /**
     * Publishes a held comment: indexed, counted and announced exactly as if it had just been
     * posted. Approving a comment that is already visible answers the same as approving it the
     * first time, so a second tap is not an error.
     *
     * @throws com.tiktok.interactionservice.exception.CommentNotFoundException when it is unknown,
     *         deleted, or a reply whose thread has been deleted
     */
    CommentResponse approveComment(Long videoId, Long commentId, Long currentUserId);

    CommentLikeResponse likeComment(Long videoId, Long commentId, Long currentUserId);

    CommentLikeResponse unlikeComment(Long videoId, Long commentId, Long currentUserId);
}
