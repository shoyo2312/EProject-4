package com.tiktok.userservice.mapper;

import com.tiktok.userservice.dto.response.UserProfileResponse;
import com.tiktok.userservice.entity.UserProfile;
import com.tiktok.userservice.service.DisplayNameCooldown;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.time.Instant;

@Mapper(componentModel = "spring", imports = {DisplayNameCooldown.class, Instant.class})
public interface UserProfileMapper {

    /** Anyone's view of a profile, the owner's included when it is reached through a list. */
    @Mapping(target = "displayNameChangeableAt", ignore = true)
    UserProfileResponse toResponse(UserProfile profile);

    /** The owner looking at their own profile: also says when their name may next change. */
    @Mapping(target = "displayNameChangeableAt",
            expression = "java(DisplayNameCooldown.changeableAt(profile.getDisplayNameChangedAt(), Instant.now()))")
    UserProfileResponse toOwnResponse(UserProfile profile);
}
