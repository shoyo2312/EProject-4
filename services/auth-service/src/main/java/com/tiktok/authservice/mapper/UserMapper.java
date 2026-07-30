package com.tiktok.authservice.mapper;

import com.tiktok.authservice.dto.response.UserResponse;
import com.tiktok.authservice.entity.User;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface UserMapper {

    UserResponse toResponse(User user);
}
