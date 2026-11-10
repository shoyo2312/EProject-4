package com.tiktok.userservice.mapper;

import com.tiktok.userservice.dto.response.UserSettingsPolicyResponse;
import com.tiktok.userservice.dto.response.UserSettingsResponse;
import com.tiktok.userservice.entity.UserSettings;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper(componentModel = "spring")
public interface UserSettingsMapper {

    /** {@code privateAccount} comes off the profile, not the settings row. The passcode hash never leaves. */
    @Mapping(target = "privateAccount", source = "privateAccount")
    @Mapping(target = "hasScreenTimePasscode", expression = "java(settings.getScreenTimePasscodeHash() != null)")
    UserSettingsResponse toResponse(UserSettings settings, boolean privateAccount);

    /** The fields other services enforce — see {@link UserSettingsPolicyResponse}. The keywords come from feed_keyword_filters. */
    UserSettingsPolicyResponse toPolicy(UserSettings settings, List<String> forYouKeywords,
                                        List<String> followingKeywords, List<String> friendsKeywords);
}
