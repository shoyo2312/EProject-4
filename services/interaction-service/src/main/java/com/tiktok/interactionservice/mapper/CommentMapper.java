package com.tiktok.interactionservice.mapper;

import com.tiktok.interactionservice.dto.response.CommentResponse;
import com.tiktok.interactionservice.entity.CommentByVideo;
import com.tiktok.interactionservice.service.CommentStickers;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring", uses = CommentStickers.class)
public interface CommentMapper {

    @Mapping(target = "commentId", source = "key.commentId")
    @Mapping(target = "videoId", source = "key.videoId")
    @Mapping(target = "likeCount", expression = "java(comment.likeCount())")
    @Mapping(target = "likedByMe", ignore = true)
    @Mapping(target = "replyCount", ignore = true)
    @Mapping(target = "status", expression = "java(comment.effectiveStatus())")
    @Mapping(target = "sticker", source = "stickerId")
    CommentResponse toResponse(CommentByVideo comment);
}
