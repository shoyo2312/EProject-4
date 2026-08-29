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
    VideoResponse toResponse(Video video);
}
