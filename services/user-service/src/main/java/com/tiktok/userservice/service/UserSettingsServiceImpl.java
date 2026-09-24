package com.tiktok.userservice.service;

import com.tiktok.userservice.dto.request.UpdateUserSettingsRequest;
import com.tiktok.userservice.dto.response.UserSettingsPolicyResponse;
import com.tiktok.userservice.dto.response.UserSettingsResponse;
import com.tiktok.userservice.entity.SettingsAudience;
import com.tiktok.userservice.entity.UserSettings;
import com.tiktok.userservice.exception.InvalidUserSettingsException;
import com.tiktok.userservice.exception.TooManyProfileIdsException;
import com.tiktok.userservice.mapper.UserSettingsMapper;
import com.tiktok.userservice.repository.UserSettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserSettingsServiceImpl implements UserSettingsService {

    private final UserSettingsRepository userSettingsRepository;
    private final UserSettingsMapper userSettingsMapper;

    @Override
    @Transactional(readOnly = true)
    public UserSettingsResponse getOwnSettings(Long userId) {
        return userSettingsMapper.toResponse(currentOrDefaults(userId));
    }

    @Override
    @Transactional
    public UserSettingsResponse updateOwnSettings(Long userId, UpdateUserSettingsRequest request) {
        UserSettings settings = currentOrDefaults(userId);

        SettingsAudience commentAudience = request.isCommentAudienceSet()
                ? audience("commentAudience", request.getCommentAudience())
                : settings.getCommentAudience();
        SettingsAudience dmAudience = request.isDmAudienceSet()
                ? audience("dmAudience", request.getDmAudience())
                : settings.getDmAudience();
        boolean weeklyDigest = request.isWeeklyScreenTimeDigestSet()
                ? required("weeklyScreenTimeDigest", request.getWeeklyScreenTimeDigest())
                : settings.isWeeklyScreenTimeDigest();

        Integer screenTimeLimitMinutes = request.isScreenTimeLimitMinutesSet()
                ? request.getScreenTimeLimitMinutes()
                : settings.getScreenTimeLimitMinutes();
        LocalTime sleepHoursStart = request.isSleepHoursStartSet()
                ? request.getSleepHoursStart()
                : settings.getSleepHoursStart();
        LocalTime sleepHoursEnd = request.isSleepHoursEndSet()
                ? request.getSleepHoursEnd()
                : settings.getSleepHoursEnd();

        requireValidSleepWindow(sleepHoursStart, sleepHoursEnd);

        settings.replaceWith(commentAudience, dmAudience, screenTimeLimitMinutes,
                sleepHoursStart, sleepHoursEnd, weeklyDigest);

        // Insert on the first write, update on the rest: currentOrDefaults hands back either a
        // managed row or a transient one, and save covers both.
        //
        // Same check-then-act race as FollowServiceImpl.follow — two devices writing for the first
        // time both read no row and both insert, and only uq_user_settings_user_id stops the
        // second. saveAndFlush forces that to surface here rather than at commit, and it is
        // reported as the 409 two concurrent writes already produce on an existing row: the
        // caller's move is identical either way, re-read and try again, and the retry finds the
        // row and updates it.
        try {
            return userSettingsMapper.toResponse(userSettingsRepository.saveAndFlush(settings));
        } catch (DataIntegrityViolationException ex) {
            throw new OptimisticLockingFailureException(
                    "Settings for user " + userId + " were created by another request", ex);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserSettingsPolicyResponse> getSettingsPolicies(List<Long> userIds) {
        // Against the raw list, before the distinct below, exactly like
        // UserProfileService.getByUserIds: what the cap guards is the query string being parsed and
        // bound, and duplicates pay for that in full.
        if (userIds.size() > UserProfileService.MAX_BATCH_IDS) {
            throw new TooManyProfileIdsException(UserProfileService.MAX_BATCH_IDS);
        }

        // Nulls dropped like getStatsByUserIds does: "ids=1,,2" binds one, and an answer carrying
        // "userId": null would be a row no caller can key by.
        Set<Long> distinctIds = userIds.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (distinctIds.isEmpty()) {
            return List.of();
        }

        Map<Long, UserSettings> stored = userSettingsRepository.findByUserIdInAndDeletedAtIsNull(distinctIds)
                .stream()
                .collect(Collectors.toMap(UserSettings::getUserId, Function.identity()));

        // Every id asked for is answered, with the defaults where there is no row — see the
        // interface for why absence is not an option here.
        return distinctIds.stream()
                .map(id -> stored.getOrDefault(id, UserSettings.defaultsFor(id)))
                .map(userSettingsMapper::toPolicy)
                .toList();
    }

    /**
     * The stored row, or a transient one carrying the defaults. The transient one is never saved
     * on the read path — {@link #getOwnSettings} only maps it — so reading somebody's settings
     * does not create a row for them.
     */
    private UserSettings currentOrDefaults(Long userId) {
        return userSettingsRepository.findByUserIdAndDeletedAtIsNull(userId)
                .orElseGet(() -> UserSettings.defaultsFor(userId));
    }

    private static SettingsAudience audience(String field, String value) {
        // Already matched against the three names by @Pattern; null is what is left, and it means
        // the client sent the key explicitly set to null on a column that has no null.
        return SettingsAudience.valueOf(required(field, value));
    }

    private static <T> T required(String field, T value) {
        if (value == null) {
            throw new InvalidUserSettingsException(field + ": cannot be null");
        }
        return value;
    }

    /**
     * Both ends or neither, and not the same minute twice. A start later than the end is left
     * alone on purpose: a window that crosses midnight is the normal case for sleep hours.
     */
    private static void requireValidSleepWindow(LocalTime start, LocalTime end) {
        if ((start == null) != (end == null)) {
            throw new InvalidUserSettingsException(
                    "sleepHoursStart and sleepHoursEnd must be set together or cleared together");
        }
        if (start != null && start.equals(end)) {
            throw new InvalidUserSettingsException("sleepHoursStart and sleepHoursEnd must differ");
        }
    }
}
