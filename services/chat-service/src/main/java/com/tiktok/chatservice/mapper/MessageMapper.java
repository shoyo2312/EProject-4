package com.tiktok.chatservice.mapper;

import com.tiktok.chatservice.dto.response.MessageResponse;
import com.tiktok.chatservice.entity.Message;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface MessageMapper {

    @Mapping(target = "sticker", ignore = true)
    @Mapping(target = "replyTo", ignore = true)
    @Mapping(target = "reactions", ignore = true)
    MessageResponse toResponse(Message message);
}
