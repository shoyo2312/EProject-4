package com.tiktok.notificationservice.mapper;

import com.tiktok.notificationservice.dto.response.NotificationResponse;
import com.tiktok.notificationservice.entity.Notification;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface NotificationMapper {

    NotificationResponse toResponse(Notification notification);
}
