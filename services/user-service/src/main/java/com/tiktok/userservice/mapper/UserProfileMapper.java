package com.tiktok.userservice.mapper;

import com.tiktok.userservice.dto.response.UserProfileResponse;
import com.tiktok.userservice.entity.UserProfile;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface UserProfileMapper {

    UserProfileResponse toResponse(UserProfile profile);
}
