package com.tiktok.userservice.mapper;

import com.tiktok.userservice.dto.response.UserSettingsPolicyResponse;
import com.tiktok.userservice.dto.response.UserSettingsResponse;
import com.tiktok.userservice.entity.UserSettings;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface UserSettingsMapper {

    /** {@code privateAccount} comes off the profile, not the settings row. */
    @Mapping(target = "privateAccount", source = "privateAccount")
    UserSettingsResponse toResponse(UserSettings settings, boolean privateAccount);

    /** The two fields other services may see — see {@link UserSettingsPolicyResponse}. */
    UserSettingsPolicyResponse toPolicy(UserSettings settings);
}
