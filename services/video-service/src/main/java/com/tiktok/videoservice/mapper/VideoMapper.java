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
    VideoResponse toResponse(Video video);

    /**
     * The same response with the classifier's numbers attached. A machine-removed video sets no
     * takedownReason, so this is the only account of why it went down.
     */
    @Mapping(target = "commentCount",
            expression = "java(video.isCommentsDisabled() ? null : video.getCommentCount())")
    VideoResponse toAdminResponse(Video video);
}
