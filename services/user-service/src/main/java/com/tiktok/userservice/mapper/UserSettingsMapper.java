package com.tiktok.userservice.mapper;

import com.tiktok.userservice.dto.response.UserSettingsPolicyResponse;
import com.tiktok.userservice.dto.response.UserSettingsResponse;
import com.tiktok.userservice.entity.UserSettings;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface UserSettingsMapper {

    UserSettingsResponse toResponse(UserSettings settings);

    /** The two fields other services may see — see {@link UserSettingsPolicyResponse}. */
    UserSettingsPolicyResponse toPolicy(UserSettings settings);
}
