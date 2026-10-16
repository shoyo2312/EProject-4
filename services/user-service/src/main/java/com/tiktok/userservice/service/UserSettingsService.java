package com.tiktok.userservice.service;

import com.tiktok.userservice.dto.request.UpdateUserSettingsRequest;
import com.tiktok.userservice.dto.response.UserSettingsPolicyResponse;
import com.tiktok.userservice.dto.response.UserSettingsResponse;

import java.util.List;

public interface UserSettingsService {

    /**
     * The caller's own settings, or the defaults when they have never changed one.
     *
     * <p>Never 404, and never a write: an account with no row reads exactly like an account that
     * set everything back to default. Profiles already have to warn that
     * {@code USER_PROFILE_NOT_FOUND} can mean "your profile has not been created yet", and a
     * second row created off the same asynchronous registration would be a second such warning.
     */
    UserSettingsResponse getOwnSettings(Long userId);

    /**
     * Applies a patch and answers the whole object as it now stands.
     *
     * <p>Inserts the row if this is the account's first write, carrying the patch over the
     * defaults. Two devices writing at once produce {@code CONCURRENT_MODIFICATION} through
     * {@code BaseEntity}'s version column.
     *
     * <p>{@code privateAccount} is kept on the profile, not in this row. Changing it announces
     * {@code UserPrivacyChangedEvent}, and switching back to public approves every pending follow
     * request — all in this one transaction. Sent before the profile exists, it is
     * {@code USER_PROFILE_NOT_FOUND}.
     */
    UserSettingsResponse updateOwnSettings(Long userId, UpdateUserSettingsRequest request);

    /**
     * The enforcement-relevant settings for a page of ids, for the services that apply them:
     * video-service resolving a video owner's comment audience, chat-service resolving a DM
     * recipient's.
     *
     * <p><b>An id with no row comes back carrying the defaults — it is never omitted.</b> This is
     * the opposite of {@link UserProfileService#getByUserIds}, which drops what the viewer cannot
     * see. Omitting here would make a caller's "no answer" indistinguishable from "no
     * restriction", and the first caller to read absence as permission has silently switched the
     * feature off.
     *
     * <p>Same cap and same {@link com.tiktok.userservice.exception.TooManyProfileIdsException} as
     * the profile batch.
     */
    List<UserSettingsPolicyResponse> getSettingsPolicies(List<Long> userIds);
}
