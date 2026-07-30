package com.tiktok.userservice.mapper;

import com.tiktok.userservice.dto.response.UserProfileResponse;
import com.tiktok.userservice.entity.UserProfile;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface UserProfileMapper {

    @Mapping(target = "followerCount", ignore = true)
    @Mapping(target = "followingCount", ignore = true)
    UserProfileResponse toResponse(UserProfile profile);
}
