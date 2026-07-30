package com.tiktok.interactionservice.mapper;

import com.tiktok.interactionservice.dto.response.CommentResponse;
import com.tiktok.interactionservice.entity.CommentByVideo;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface CommentMapper {

    @Mapping(target = "commentId", source = "key.commentId")
    @Mapping(target = "videoId", source = "key.videoId")
    CommentResponse toResponse(CommentByVideo comment);
}
