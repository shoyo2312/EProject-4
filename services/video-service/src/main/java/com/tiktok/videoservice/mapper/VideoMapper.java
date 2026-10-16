package com.tiktok.videoservice.mapper;

import com.tiktok.videoservice.dto.response.VideoResponse;
import com.tiktok.videoservice.entity.Video;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface VideoMapper {

    // Comments off => no comment total leaves the service. One place, so every read path
    // (getById, getByIds, feed, listByUser, the two owner mutations) is covered at once.
    @Mapping(target = "commentCount",
            expression = "java(video.isCommentsDisabled() ? null : video.getCommentCount())")
    @Mapping(target = "moderation", ignore = true)
    // Same reasoning as moderation: which word sent a video to review is not for its uploader.
    @Mapping(target = "textModeration", ignore = true)
    // The raw upload path is an internal MinIO object key; it never leaves on a public read.
    @Mapping(target = "rawFileUrl", ignore = true)
    // A deleted video is unreachable on every public path, so this would always be null there
    // anyway; ignored rather than mapped so no future public route can start leaking it.
    @Mapping(target = "deletedAt", ignore = true)
    // Internal outbox bookkeeping, same reasoning as deletedAt just above.
    @Mapping(target = "purgeEventPublishedAt", ignore = true)
    // Not the video's own state: the owner's account setting, laid on per request after the
    // cache by VideoServiceImpl.withAccountComments.
    @Mapping(target = "accountCommentsOff", ignore = true)
    VideoResponse toResponse(Video video);

    /**
     * The same response with the classifier's numbers and the text rules' flags attached. A
     * machine-removed video sets no takedownReason, so this is the only account of why it went
     * down — or, for a video held over its caption, why it is waiting.
     */
    @Mapping(target = "commentCount",
            expression = "java(video.isCommentsDisabled() ? null : video.getCommentCount())")
    @Mapping(target = "accountCommentsOff", ignore = true)
    VideoResponse toAdminResponse(Video video);
}
