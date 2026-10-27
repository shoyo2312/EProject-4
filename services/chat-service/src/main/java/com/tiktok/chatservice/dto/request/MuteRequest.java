package com.tiktok.chatservice.dto.request;

import jakarta.validation.constraints.NotNull;

import java.time.Duration;

/** How long to silence a conversation. The end is worked out by the server, from its own clock. */
public record MuteRequest(@NotNull Length duration) {

    public enum Length {
        ONE_HOUR(Duration.ofHours(1)),
        EIGHT_HOURS(Duration.ofHours(8)),
        TWENTY_FOUR_HOURS(Duration.ofHours(24)),
        /** No end: until the viewer unmutes. */
        INDEFINITE(null);

        private final Duration span;

        Length(Duration span) {
            this.span = span;
        }

        /** Null for {@link #INDEFINITE}. */
        public Duration span() {
            return span;
        }
    }
}
