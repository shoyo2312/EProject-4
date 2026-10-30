package com.tiktok.chatservice.mapper;

import com.tiktok.chatservice.dto.response.MessageResponse;
import com.tiktok.chatservice.entity.Message;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface MessageMapper {

    @Mapping(target = "video", ignore = true)
    @Mapping(target = "story", ignore = true)
    @Mapping(target = "replayed", ignore = true)
    @Mapping(target = "sticker", ignore = true)
    @Mapping(target = "replyTo", ignore = true)
    @Mapping(target = "reactions", ignore = true)
    MessageResponse toResponse(Message message);
}
