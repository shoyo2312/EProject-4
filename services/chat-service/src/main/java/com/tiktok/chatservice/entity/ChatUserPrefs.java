package com.tiktok.chatservice.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What one person has decided about their own view of their conversations — kept in a document of
 * their own ({@code _id} is the user id) instead of on the shared {@code Conversation}, so one side's
 * mute, pin or delete can never leak into the other's copy and never contends with the writes both
 * sides make to the conversation on every message.
 *
 * <p>The pin limit lives here for the same reason: "at most three" is a statement about one user's
 * list, and a single document is the one place MongoDB can enforce it atomically without
 * multi-document transactions. See {@code ChatUserPrefsRepositoryImpl#pin}.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "chat_user_prefs")
public class ChatUserPrefs {

    /** How many conversations a person can keep pinned. */
    public static final int MAX_PINS = 3;

    /** The user id. */
    @Id
    private Long id;

    @Builder.Default
    private List<Pin> pins = new ArrayList<>();

    /** Conversation id to its mute. Absent means never muted; a lapsed timed mute stays until replaced. */
    @Builder.Default
    private Map<String, Mute> mutes = new HashMap<>();

    /**
     * Conversation id to the moment the viewer deleted it from their side. Messages sent at or before
     * it are not theirs to see any more; later ones are. Only ever moves forward.
     */
    @Builder.Default
    private Map<String, Instant> clearedAt = new HashMap<>();

    public record Pin(String conversationId, Instant pinnedAt) {
    }

    /** {@code mode} is TIMED or INDEFINITE only; "not muted" is the absence of a record. */
    public record Mute(MuteMode mode, Instant until) {
    }

    public static ChatUserPrefs empty(Long userId) {
        return ChatUserPrefs.builder().id(userId).build();
    }

    public Instant pinnedAt(String conversationId) {
        if (pins == null) {
            return null;
        }
        return pins.stream().filter(pin -> pin.conversationId().equals(conversationId))
                .map(Pin::pinnedAt).findFirst().orElse(null);
    }

    /** The answer at {@code now}: a timed mute whose end has passed is no mute at all. */
    public MuteMode muteModeAt(String conversationId, Instant now) {
        Mute mute = mutes == null ? null : mutes.get(conversationId);
        if (mute == null || mute.mode() == null) {
            return MuteMode.NONE;
        }
        if (mute.mode() == MuteMode.INDEFINITE) {
            return MuteMode.INDEFINITE;
        }
        return mute.until() != null && mute.until().isAfter(now) ? MuteMode.TIMED : MuteMode.NONE;
    }

    /** Only while a timed mute is in force; null for none and for indefinite. */
    public Instant mutedUntilAt(String conversationId, Instant now) {
        return muteModeAt(conversationId, now) == MuteMode.TIMED ? mutes.get(conversationId).until() : null;
    }

    public Instant clearedAt(String conversationId) {
        return clearedAt == null ? null : clearedAt.get(conversationId);
    }
}
