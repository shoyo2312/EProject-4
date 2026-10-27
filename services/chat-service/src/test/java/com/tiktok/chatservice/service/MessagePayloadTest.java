package com.tiktok.chatservice.service;

import com.tiktok.chatservice.dto.request.SendMessageRequest;
import com.tiktok.chatservice.entity.MessageType;
import com.tiktok.chatservice.exception.InvalidMessageException;
import com.tiktok.chatservice.exception.InvalidStickerException;
import com.tiktok.chatservice.sticker.StickerCatalog;
import com.tiktok.common.exception.BadRequestException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MessagePayloadTest {

    private final StickerCatalog catalog = StickerCatalog.load("http://localhost:9000", "video-media");
    private final ObjectMapper json = JsonMapper.builder().build();

    private MessagePayload of(SendMessageRequest request) {
        return MessagePayload.from(request, catalog);
    }

    @Test
    void aRequestWithOnlyContentIsTheTextMessageEveryOlderClientSends() throws Exception {
        SendMessageRequest request = json.readValue("{\"content\":\"hello\"}", SendMessageRequest.class);

        MessagePayload payload = of(request);

        assertThat(payload.type()).isEqualTo(MessageType.TEXT);
        assertThat(payload.content()).isEqualTo("hello");
        assertThat(payload.stickerId()).isNull();
    }

    @Test
    void emojiAreJustCharactersInATextMessage() {
        MessagePayload payload = of(new SendMessageRequest("hi 😀🎉"));
        assertThat(payload.type()).isEqualTo(MessageType.TEXT);
        assertThat(payload.content()).isEqualTo("hi 😀🎉");
    }

    @Test
    void aStickerNeedsOnlyItsId() throws Exception {
        SendMessageRequest request = json.readValue("{\"type\":\"STICKER\",\"stickerId\":\"fire\"}", SendMessageRequest.class);

        MessagePayload payload = of(request);

        assertThat(payload.type()).isEqualTo(MessageType.STICKER);
        assertThat(payload.stickerId()).isEqualTo("fire");
        assertThat(payload.content()).isNull();
    }

    @Test
    void blankOrMissingOrTooLongTextIsRefusedWithTheCodeClientsAlreadyKnow() {
        for (SendMessageRequest bad : new SendMessageRequest[]{
                new SendMessageRequest((String) null), new SendMessageRequest(""), new SendMessageRequest("   	 "),
                new SendMessageRequest(MessageType.TEXT, null, null)}) {
            assertThatThrownBy(() -> of(bad)).isInstanceOf(BadRequestException.class)
                    .extracting("code").isEqualTo("VALIDATION_ERROR");
        }
        assertThat(of(new SendMessageRequest("x".repeat(2000))).content()).hasSize(2000);
        assertThatThrownBy(() -> of(new SendMessageRequest("x".repeat(2001))))
                .extracting("code").isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void aStickerWithAnUnknownMissingOrBlankIdIsRefused() {
        for (String id : new String[]{"nope", null, "", " ", "../../raw/x", "http://evil/x.png"}) {
            assertThatThrownBy(() -> of(SendMessageRequest.sticker(id))).isInstanceOf(InvalidStickerException.class)
                    .extracting("code").isEqualTo("INVALID_STICKER");
        }
    }

    @Test
    void contradictoryFieldsAreRefused() {
        assertThatThrownBy(() -> of(new SendMessageRequest(MessageType.STICKER, "hello", "fire")))
                .isInstanceOf(InvalidMessageException.class).extracting("code").isEqualTo("INVALID_MESSAGE");
        assertThatThrownBy(() -> of(new SendMessageRequest(MessageType.STICKER, "", "fire")))
                .isInstanceOf(InvalidMessageException.class);
        assertThatThrownBy(() -> of(new SendMessageRequest(MessageType.TEXT, "hello", "fire")))
                .isInstanceOf(InvalidMessageException.class);
        assertThatThrownBy(() -> of(new SendMessageRequest(null, "hello", "fire")))
                .isInstanceOf(InvalidMessageException.class);
    }

    @Test
    void anUnknownTypeNeverReachesTheService() {
        assertThatThrownBy(() -> json.readValue("{\"type\":\"GIF\",\"content\":\"x\"}", SendMessageRequest.class))
                .isInstanceOf(Exception.class);
    }
}
