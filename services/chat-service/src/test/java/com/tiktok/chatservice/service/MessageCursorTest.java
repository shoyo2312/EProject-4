package com.tiktok.chatservice.service;

import com.tiktok.chatservice.entity.Message;
import com.tiktok.chatservice.exception.InvalidCursorException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MessageCursorTest {

    @Test
    void roundTrip_keepsTheMillisecondAndTheTiebreak() {
        Message last = Message.builder().id("7012345678901234567")
                .sentAt(Instant.parse("2026-10-07T10:00:00.123Z")).build();

        MessageCursor decoded = MessageCursor.decode(MessageCursor.of(last).encode());

        assertThat(decoded).isEqualTo(new MessageCursor(last.getSentAt(), last.getId()));
    }

    @Test
    void absentCursor_meansTheNewestPage() {
        assertThat(MessageCursor.decode(null)).isNull();
        assertThat(MessageCursor.decode(" ")).isNull();
    }

    @Test
    void legacyCursor_decodesWithoutATiebreak() {
        Instant sentAt = Instant.parse("2026-10-07T10:00:00.123Z");
        String legacy = Base64.getEncoder().encodeToString(sentAt.toString().getBytes(StandardCharsets.UTF_8));

        assertThat(MessageCursor.decode(legacy)).isEqualTo(new MessageCursor(sentAt, null));
    }

    @Test
    void mangledCursor_isABadRequest() {
        for (String raw : new String[] {"abc_123", "_123", "123_", "not base64!", "bm90IGEgdGltZQ=="}) {
            assertThatThrownBy(() -> MessageCursor.decode(raw))
                    .as(raw)
                    .isInstanceOf(InvalidCursorException.class);
        }
    }
}
