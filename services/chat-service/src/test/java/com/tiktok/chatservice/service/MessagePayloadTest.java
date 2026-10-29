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

    @Test
    void videoRequiresOnlyAnIdAndTrimsItsOptionalCaption() {
        MessagePayload video = of(SendMessageRequest.video("123", "  note  ", "key"));
        assertThat(video.type()).isEqualTo(MessageType.VIDEO);
        assertThat(video.videoId()).isEqualTo("123");
        assertThat(video.content()).isEqualTo("note");
        assertThat(video.stickerId()).isNull();
        assertThat(of(SendMessageRequest.video("123", "   ", null)).content()).isNull();
        assertThat(of(SendMessageRequest.video("123", null, null)).content()).isNull();
    }

    @Test
    void videoCaptionLimitIs300Utf16UnitsAfterTrim() {
        assertThat(of(SendMessageRequest.video("123", "  " + "x".repeat(300) + "  ", null)).content()).hasSize(300);
        assertThatThrownBy(() -> of(SendMessageRequest.video("123", "x".repeat(301), null)))
                .isInstanceOf(InvalidMessageException.class);
        assertThatThrownBy(() -> of(SendMessageRequest.video("123", "\uD83D\uDE00".repeat(151), null)))
                .isInstanceOf(InvalidMessageException.class);
        assertThat(of(SendMessageRequest.video("123", " ".repeat(2100), null)).content()).isNull();
    }

    @Test
    void videoContradictionsAndIdsOnOtherTypesAreRejected() {
        for (String id : new String[]{null, "", " "}) {
            assertThatThrownBy(() -> of(SendMessageRequest.video(id, null, null)))
                    .isInstanceOf(InvalidMessageException.class);
        }
        assertThatThrownBy(() -> of(new SendMessageRequest(MessageType.VIDEO, null, "fire", null, "123", null)))
                .isInstanceOf(InvalidMessageException.class);
        assertThatThrownBy(() -> of(new SendMessageRequest(MessageType.TEXT, "hi", null, null, "123", null)))
                .isInstanceOf(InvalidMessageException.class);
        assertThatThrownBy(() -> of(new SendMessageRequest(MessageType.STICKER, null, "fire", null, "123", null)))
                .isInstanceOf(InvalidMessageException.class);
    }

    @Test
    void clientSuppliedCardMetadataIsIgnoredAndReplyFactoryKeepsIds() {
        SendMessageRequest request = json.readValue("{\"type\":\"VIDEO\",\"videoId\":\"123\",\"thumbnailUrl\":\"evil\",\"ownerId\":9}", SendMessageRequest.class);
        assertThat(of(request).videoId()).isEqualTo("123");
        SendMessageRequest reply = SendMessageRequest.video("123", "note", "key").replyingTo("original");
        assertThat(reply.videoId()).isEqualTo("123");
        assertThat(reply.clientRequestId()).isEqualTo("key");
        assertThat(reply.replyToMessageId()).isEqualTo("original");
    }

    @Test
    void beanValidationKeepsTheTextSizeConstraintWithoutApplyingItBeforeVideoTrim() {
        try (var factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validate(new SendMessageRequest("x".repeat(2001)))).singleElement()
                    .satisfies(v -> assertThat(v.getPropertyPath().toString()).isEqualTo("content"));
            assertThat(validator.validate(SendMessageRequest.video("123", " ".repeat(2100) + "note", null))).isEmpty();
            assertThat(of(SendMessageRequest.video("123", " ".repeat(2100) + "note", null)).content()).isEqualTo("note");
        }
    }
}
