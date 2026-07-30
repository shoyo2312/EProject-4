package com.tiktok.chatservice.mapper;

import com.tiktok.chatservice.dto.response.MessageResponse;
import com.tiktok.chatservice.entity.Message;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface MessageMapper {

    MessageResponse toResponse(Message message);
}
