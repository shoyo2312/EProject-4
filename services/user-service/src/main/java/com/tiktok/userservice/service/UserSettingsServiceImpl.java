package com.tiktok.userservice.service;

import com.tiktok.crypto.hash.HashUtils;
import com.tiktok.moderation.rules.Blocklist;
import com.tiktok.userservice.dto.request.UpdateUserSettingsRequest;
import com.tiktok.userservice.dto.response.UserSettingsPolicyResponse;
import com.tiktok.userservice.dto.response.UserSettingsResponse;
import com.tiktok.userservice.entity.SettingsAudience;
import com.tiktok.userservice.entity.UserSettings;
import com.tiktok.userservice.event.producer.UserEventPublisher;
import com.tiktok.userservice.exception.InvalidUserSettingsException;
import com.tiktok.userservice.exception.TooManyProfileIdsException;
import com.tiktok.userservice.exception.UserProfileNotFoundException;
import com.tiktok.userservice.mapper.UserSettingsMapper;
import com.tiktok.userservice.repository.UserProfileRepository;
import com.tiktok.userservice.repository.UserSettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.util.LinkedHashMap;
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
    private final UserProfileRepository userProfileRepository;
    private final UserEventPublisher userEventPublisher;
    private final FollowService followService;

    @Override
    @Transactional(readOnly = true)
    public UserSettingsResponse getOwnSettings(Long userId) {
        return userSettingsMapper.toResponse(currentOrDefaults(userId),
                userProfileRepository.existsByUserIdAndDeletedAtIsNullAndPrivateAccountTrue(userId));
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
        // Null here means the key was absent: leave the flag where it is.
        Boolean privateAccount = request.isPrivateAccountSet()
                ? required("privateAccount", request.getPrivateAccount())
                : null;

        Integer screenTimeLimitMinutes = request.isScreenTimeLimitMinutesSet()
                ? request.getScreenTimeLimitMinutes()
                : settings.getScreenTimeLimitMinutes();
        List<Integer> screenTimeLimitsByDay = request.isScreenTimeLimitsByDaySet()
                ? request.getScreenTimeLimitsByDay()
                : settings.getScreenTimeLimitsByDay();
        String passcodeHash = screenTimePasscodeHash(settings, screenTimeLimitMinutes, screenTimeLimitsByDay, request);
        LocalTime sleepHoursStart = request.isSleepHoursStartSet()
                ? request.getSleepHoursStart()
                : settings.getSleepHoursStart();
        // As on TikTok, sleep hours always last 8 hours. Derived here so no client can store
        // anything else; clearing the start clears both.
        LocalTime sleepHoursEnd = sleepHoursStart == null ? null : sleepHoursStart.plusHours(8);

        boolean pushLikes = request.isPushLikesSet()
                ? required("pushLikes", request.getPushLikes())
                : settings.isPushLikes();
        boolean pushComments = request.isPushCommentsSet()
                ? required("pushComments", request.getPushComments())
                : settings.isPushComments();
        boolean pushNewFollowers = request.isPushNewFollowersSet()
                ? required("pushNewFollowers", request.getPushNewFollowers())
                : settings.isPushNewFollowers();
        boolean inAppLikes = request.isInAppLikesSet()
                ? required("inAppLikes", request.getInAppLikes())
                : settings.isInAppLikes();
        boolean inAppComments = request.isInAppCommentsSet()
                ? required("inAppComments", request.getInAppComments())
                : settings.isInAppComments();
        boolean inAppNewFollowers = request.isInAppNewFollowersSet()
                ? required("inAppNewFollowers", request.getInAppNewFollowers())
                : settings.isInAppNewFollowers();
        boolean inAppReposts = request.isInAppRepostsSet()
                ? required("inAppReposts", request.getInAppReposts())
                : settings.isInAppReposts();

        boolean filterSpamOffensive = request.isFilterSpamOffensiveSet()
                ? required("filterSpamOffensive", request.getFilterSpamOffensive())
                : settings.isFilterSpamOffensive();
        boolean filterAllComments = request.isFilterAllCommentsSet()
                ? required("filterAllComments", request.getFilterAllComments())
                : settings.isFilterAllComments();
        List<String> commentKeywords = request.isCommentKeywordsSet()
                ? commentKeywords(request.getCommentKeywords())
                : settings.getCommentKeywords();

        settings.replaceWith(commentAudience, dmAudience, sleepHoursStart, sleepHoursEnd, weeklyDigest);
        if (!Objects.equals(passcodeHash, settings.getScreenTimePasscodeHash())) {
            // A new passcode, or none: wrong guesses at the old one no longer count.
            settings.replacePasscodeAttempts(0, null);
        }
        settings.replaceScreenTimeLimit(screenTimeLimitMinutes, screenTimeLimitsByDay, passcodeHash);
        settings.replaceCommentFilters(filterSpamOffensive, filterAllComments, commentKeywords);
        settings.replaceNotificationSwitches(pushLikes, pushComments, pushNewFollowers,
                inAppLikes, inAppComments, inAppNewFollowers, inAppReposts);

        // Insert on the first write, update on the rest: currentOrDefaults hands back either a
        // managed row or a transient one, and save covers both.
        //
        // Same check-then-act race as FollowServiceImpl.follow — two devices writing for the first
        // time both read no row and both insert, and only uq_user_settings_user_id stops the
        // second. saveAndFlush forces that to surface here rather than at commit, and it is
        // reported as the 409 two concurrent writes already produce on an existing row: the
        // caller's move is identical either way, re-read and try again, and the retry finds the
        // row and updates it.
        UserSettings saved;
        try {
            saved = userSettingsRepository.saveAndFlush(settings);
        } catch (DataIntegrityViolationException ex) {
            throw new OptimisticLockingFailureException(
                    "Settings for user " + userId + " were created by another request", ex);
        }

        // After the settings row, never before it: the flag's bulk update clears the persistence
        // context, which would detach the row mid-write. One transaction either way, so a failure
        // on either side leaves both where they were.
        boolean isPrivate = privateAccount == null
                ? userProfileRepository.existsByUserIdAndDeletedAtIsNullAndPrivateAccountTrue(userId)
                : changePrivacy(userId, privateAccount);
        return userSettingsMapper.toResponse(saved, isPrivate);
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
     * Flips the flag only where it differs, in the database rather than after a read, so two
     * PATCHes racing to the same value announce one change between them.
     *
     * @return the value now stored
     */
    private boolean changePrivacy(Long userId, boolean privateAccount) {
        if (userProfileRepository.updatePrivateAccount(userId, privateAccount) == 0) {
            // Nothing matched: either the flag already had this value — a no-op that announces
            // nothing — or there is no profile to put it on.
            if (!userProfileRepository.existsByUserIdAndDeletedAtIsNull(userId)) {
                throw new UserProfileNotFoundException(userId);
            }
            return privateAccount;
        }

        userEventPublisher.publishPrivacyChanged(userId, privateAccount);
        if (!privateAccount) {
            // Nothing gates following any more, and these people already asked.
            followService.approveAllFollowRequests(userId);
        }
        return privateAccount;
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

    /**
     * The list as it will be stored: already sanitized and length-checked by the request, then each
     * one checked for something to match — a keyword of only emoji or punctuation would never hold a
     * thing, and the creator would believe it did — and duplicates dropped by the key the matcher
     * reads them by, so "Spoiler" and "spoiler" are one keyword while "tạch" and "tach" (which
     * match differently) stay two. The first spelling is the one kept.
     */
    private static List<String> commentKeywords(List<String> keywords) {
        required("commentKeywords", keywords);
        Map<String, String> byKey = new LinkedHashMap<>();
        for (String keyword : keywords) {
            if (!Blocklist.isMatchable(keyword)) {
                throw new InvalidUserSettingsException(
                        "commentKeywords: '" + keyword + "' has no letters or digits to match");
            }
            byKey.putIfAbsent(Blocklist.keyOf(keyword), keyword);
        }
        return List.copyOf(byKey.values());
    }

    /**
     * The passcode only guards continuing past the daily limit, so it lives exactly as long as the
     * limit: turning the limit on needs one, changing the limit keeps it, turning the limit off
     * forgets it — which is also how a forgotten one is replaced. Changing or turning off the limit
     * never asks for it.
     *
     * @return the hash to store, null when the limit is off
     */
    private static String screenTimePasscodeHash(UserSettings settings, Integer minutes, List<Integer> byDay,
                                                 UpdateUserSettingsRequest request) {
        if (minutes != null && byDay != null) {
            throw new InvalidUserSettingsException(
                    "screenTimeLimitMinutes and screenTimeLimitsByDay: set one daily limit, not both");
        }
        if (minutes == null && byDay == null) {
            return null;
        }
        String passcode = request.isScreenTimePasscodeSet() ? request.getScreenTimePasscode() : null;
        if (passcode != null) {
            return HashUtils.bcryptHash(passcode);
        }
        if (settings.getScreenTimePasscodeHash() == null) {
            throw new InvalidUserSettingsException("screenTimePasscode: required to turn on the daily limit");
        }
        return settings.getScreenTimePasscodeHash();
    }

    private static <T> T required(String field, T value) {
        if (value == null) {
            throw new InvalidUserSettingsException(field + ": cannot be null");
        }
        return value;
    }
}
