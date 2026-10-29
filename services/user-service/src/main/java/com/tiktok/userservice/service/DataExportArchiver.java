package com.tiktok.userservice.service;

import com.tiktok.userservice.client.DataExportSources;
import com.tiktok.userservice.entity.DataExportCategory;
import com.tiktok.userservice.entity.SettingsAudience;
import com.tiktok.userservice.entity.UserProfile;
import com.tiktok.userservice.entity.UserSettings;
import com.tiktok.userservice.repository.UserBlockRepository;
import com.tiktok.userservice.repository.UserFollowRepository;
import com.tiktok.userservice.repository.UserMuteRepository;
import com.tiktok.userservice.repository.UserProfileRepository;
import com.tiktok.userservice.repository.UserSettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import static com.tiktok.userservice.service.DataExportWriter.date;

/**
 * Gathers what each requested category holds: profile, settings and relationships from this
 * service's own tables, the rest from the services that own it (see {@link DataExportSources}).
 * Accounts are named by username, looked up here — this service is the one that has them.
 */
@Component
@RequiredArgsConstructor
public class DataExportArchiver {

    /** Ids per username lookup, so a long follower list is a few queries rather than one huge IN. */
    private static final int LOOKUP_CHUNK = 500;

    private static final Pageable NEWEST_FIRST = Pageable.unpaged(Sort.by(Sort.Direction.DESC, "createdAt"));

    private final UserProfileRepository userProfileRepository;
    private final UserSettingsRepository userSettingsRepository;
    private final UserFollowRepository userFollowRepository;
    private final UserBlockRepository userBlockRepository;
    private final UserMuteRepository userMuteRepository;
    private final DataExportSources sources;

    public List<DataExportSection> collect(Long userId, List<DataExportCategory> categories) {
        return categories.stream().map(category -> switch (category) {
            case DIRECT_MESSAGES -> directMessages(userId);
            case LIKES_AND_FAVORITES -> likesAndFavorites(userId);
            case POSTS -> posts(userId);
            case PROFILE_AND_SETTINGS -> profileAndSettings(userId);
            case YOUR_ACTIVITY -> yourActivity(userId);
        }).toList();
    }

    private DataExportSection profileAndSettings(Long userId) {
        List<Map<String, String>> profile = userProfileRepository.findByUserIdAndDeletedAtIsNull(userId)
                .map(DataExportArchiver::profileRecord)
                .map(List::of)
                .orElse(List.of());
        UserSettings settings = userSettingsRepository.findByUserIdAndDeletedAtIsNull(userId)
                .orElseGet(() -> UserSettings.defaultsFor(userId));
        return new DataExportSection("Profile and Settings", List.of(
                new DataExportSection.File("Profile Info", profile),
                new DataExportSection.File("Settings", List.of(settingsRecord(settings)))));
    }

    private static Map<String, String> profileRecord(UserProfile profile) {
        Map<String, String> record = new LinkedHashMap<>();
        record.put("Username", text(profile.getUsername()));
        record.put("Name", text(profile.getDisplayName()));
        record.put("Bio", text(profile.getBio()));
        record.put("Profile photo", text(profile.getAvatarUrl()));
        record.put("Private account", yesNo(profile.isPrivateAccount()));
        record.put("Followers", String.valueOf(profile.getFollowerCount()));
        record.put("Following", String.valueOf(profile.getFollowingCount()));
        record.put("Joined", date(profile.getCreatedAt()));
        return record;
    }

    private static Map<String, String> settingsRecord(UserSettings settings) {
        Map<String, String> record = new LinkedHashMap<>();
        record.put("Who can comment", audience(settings.getCommentAudience()));
        record.put("Who can send you direct messages", audience(settings.getDmAudience()));
        record.put("Daily screen time limit", settings.getScreenTimeLimitMinutes() == null
                ? "Off" : settings.getScreenTimeLimitMinutes() + " minutes");
        record.put("Sleep reminder", settings.getSleepHoursStart() == null || settings.getSleepHoursEnd() == null
                ? "Off" : settings.getSleepHoursStart() + " – " + settings.getSleepHoursEnd());
        record.put("Weekly screen time updates", yesNo(settings.isWeeklyScreenTimeDigest()));
        record.put("Filter spam and offensive comments", yesNo(settings.isFilterSpamOffensive()));
        record.put("Filter all comments", yesNo(settings.isFilterAllComments()));
        record.put("Filter keywords", String.join(", ", settings.getCommentKeywords()));
        return record;
    }

    private DataExportSection posts(Long userId) {
        List<Map<String, String>> records = sources.videosOf(userId).stream().map(video -> {
            Map<String, String> record = new LinkedHashMap<>();
            record.put("Date", date(video.createdAt()));
            record.put("Video ID", video.id());
            record.put("Title", text(video.title()));
            record.put("Description", text(video.description()));
            record.put("Hashtags", video.tags() == null ? "" : String.join(", ", video.tags()));
            record.put("Status", text(video.status()));
            record.put("Who can watch", text(video.visibility()));
            record.put("Duration", video.durationSeconds() == null ? "" : video.durationSeconds() + "s");
            record.put("Views", String.valueOf(video.viewCount()));
            record.put("Likes", String.valueOf(video.likeCount()));
            record.put("Comments", video.commentCount() == null ? "Off" : String.valueOf(video.commentCount()));
            record.put("Cover", text(video.thumbnailUrl()));
            record.put("Link", text(video.hlsUrl()));
            return record;
        }).toList();
        return new DataExportSection("Posts", List.of(new DataExportSection.File("Posts", records)));
    }

    private DataExportSection likesAndFavorites(Long userId) {
        return new DataExportSection("Likes and Favorites", List.of(
                new DataExportSection.File("Like List", videoActivity(sources.activityOf(userId, "likes"))),
                new DataExportSection.File("Favorite Videos", videoActivity(sources.activityOf(userId, "saves")))));
    }

    private DataExportSection directMessages(Long userId) {
        List<DataExportSources.Conversation> conversations = sources.conversationsOf(userId);
        Map<DataExportSources.Conversation, List<DataExportSources.Message>> messages = new LinkedHashMap<>();
        List<Long> accounts = new ArrayList<>();
        for (DataExportSources.Conversation conversation : conversations) {
            messages.put(conversation, sources.messagesOf(conversation.id(), userId));
            accounts.addAll(conversation.participantIds());
        }
        Map<Long, String> usernames = usernames(accounts);

        List<Map<String, String>> records = new ArrayList<>();
        messages.forEach((conversation, history) -> {
            String with = conversation.participantIds().stream()
                    .filter(id -> !id.equals(userId))
                    .findFirst()
                    .map(id -> username(usernames, id))
                    .orElse("");
            // Oldest first within a conversation, the way a chat reads; chat-service pages newest first.
            for (int i = history.size() - 1; i >= 0; i--) {
                DataExportSources.Message message = history.get(i);
                Map<String, String> record = new LinkedHashMap<>();
                record.put("Date", date(message.sentAt()));
                record.put("Chat with", with);
                record.put("From", username(usernames, message.senderId()));
                record.put("Message", messageText(message));
                if ("VIDEO".equals(message.type())) {
                    record.put("Type", "VIDEO");
                    record.put("Video ID", text(message.videoId()));
                }
                records.add(record);
            }
        });
        return new DataExportSection("Direct Messages", List.of(new DataExportSection.File("Chat History", records)));
    }

    private DataExportSection yourActivity(Long userId) {
        var following = userFollowRepository.findByFollowerIdAndDeletedAtIsNull(userId, NEWEST_FIRST).getContent();
        var followers = userFollowRepository.findByFollowingIdAndDeletedAtIsNull(userId, NEWEST_FIRST).getContent();
        var blocked = userBlockRepository.findByBlockerIdAndDeletedAtIsNull(userId, NEWEST_FIRST).getContent();
        var muted = userMuteRepository.findByMuterIdAndDeletedAtIsNull(userId, NEWEST_FIRST).getContent();

        List<Long> accounts = new ArrayList<>();
        following.forEach(f -> accounts.add(f.getFollowingId()));
        followers.forEach(f -> accounts.add(f.getFollowerId()));
        blocked.forEach(b -> accounts.add(b.getBlockedId()));
        muted.forEach(m -> accounts.add(m.getMutedId()));
        Map<Long, String> usernames = usernames(accounts);

        return new DataExportSection("Your Activity", List.of(
                new DataExportSection.File("Following", accountList(following, f -> f.getFollowingId(),
                        f -> f.getCreatedAt(), usernames)),
                new DataExportSection.File("Followers", accountList(followers, f -> f.getFollowerId(),
                        f -> f.getCreatedAt(), usernames)),
                new DataExportSection.File("Reposts", videoActivity(sources.activityOf(userId, "reposts"))),
                new DataExportSection.File("Blocked Accounts", accountList(blocked, b -> b.getBlockedId(),
                        b -> b.getCreatedAt(), usernames)),
                new DataExportSection.File("Muted Accounts", accountList(muted, m -> m.getMutedId(),
                        m -> m.getCreatedAt(), usernames))));
    }

    private static List<Map<String, String>> videoActivity(List<DataExportSources.Activity> activity) {
        return activity.stream().map(a -> {
            Map<String, String> record = new LinkedHashMap<>();
            record.put("Date", date(a.at()));
            record.put("Video ID", String.valueOf(a.videoId()));
            return record;
        }).toList();
    }

    private static <T> List<Map<String, String>> accountList(List<T> rows, Function<T, Long> account,
                                                             Function<T, Instant> at, Map<Long, String> usernames) {
        return rows.stream().map(row -> {
            Map<String, String> record = new LinkedHashMap<>();
            record.put("Date", date(at.apply(row)));
            record.put("Username", username(usernames, account.apply(row)));
            return record;
        }).toList();
    }

    private Map<Long, String> usernames(Collection<Long> userIds) {
        List<Long> ids = userIds.stream().filter(Objects::nonNull).distinct().toList();
        Map<Long, String> usernames = new HashMap<>();
        for (int from = 0; from < ids.size(); from += LOOKUP_CHUNK) {
            List<Long> chunk = ids.subList(from, Math.min(from + LOOKUP_CHUNK, ids.size()));
            userProfileRepository.findByUserIdInAndDeletedAtIsNull(chunk).forEach(profile -> {
                if (profile.getUsername() != null) {
                    usernames.put(profile.getUserId(), profile.getUsername());
                }
            });
        }
        return usernames;
    }

    /** An account whose profile is gone is still named, by id, so the row keeps its meaning. */
    private static String username(Map<Long, String> usernames, Long userId) {
        String username = usernames.get(userId);
        return username != null ? username : "Deleted account (" + userId + ")";
    }

    private static String audience(SettingsAudience audience) {
        return switch (audience) {
            case EVERYONE -> "Everyone";
            case FRIENDS -> "Friends";
            case NO_ONE -> "No one";
        };
    }

    private static String yesNo(boolean value) {
        return value ? "On" : "Off";
    }

    /** A sticker has no text; the file says which one was sent rather than leaving the cell empty. */
    private static String messageText(DataExportSources.Message message) {
        if ("STICKER".equals(message.type())) {
            return message.sticker() == null ? "[Sticker]" : "[Sticker: " + message.sticker().name() + "]";
        }
        return text(message.content());
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
