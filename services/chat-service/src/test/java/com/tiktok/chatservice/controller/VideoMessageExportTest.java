package com.tiktok.chatservice.controller;

import com.tiktok.chatservice.dto.response.*;
import com.tiktok.chatservice.entity.MessageType;
import com.tiktok.chatservice.repository.ConversationRepository;
import com.tiktok.chatservice.service.MessageService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class VideoMessageExportTest {
    @Test
    void videoExportContainsIdCaptionAndTimeWithoutCardMediaAndPreservesOtherShapes() {
        MessageService service = mock(MessageService.class);
        var controller = new InternalChatExportController(mock(ConversationRepository.class), service);
        var video = new MessageResponse("v", "c", 1L, "note", Instant.EPOCH, MessageType.VIDEO, null, null)
                .withVideo(new VideoCard("123", "AVAILABLE", 7L, "secret-thumb", "secret-title", 10));
        var text = new MessageResponse("t", "c", 1L, "text", Instant.EPOCH, MessageType.TEXT, null, null);
        var sticker = new MessageResponse("s", "c", 1L, null, Instant.EPOCH, MessageType.STICKER, "fire", null);
        when(service.exportMessages("c", 1L, null, 100)).thenReturn(new MessagePageResponse(List.of(video, text, sticker), null, false));
        var json = JsonMapper.builder().findAndAddModules().build();
        var tree = json.readTree(json.writeValueAsString(controller.messages("c", 1L, null, 100)));
        var items = tree.get("data").get("items");
        assertThat(items.get(0).get("type").asText()).isEqualTo("VIDEO");
        assertThat(items.get(0).get("videoId").asText()).isEqualTo("123");
        assertThat(items.get(0).get("content").asText()).isEqualTo("note");
        assertThat(items.get(0).has("sentAt")).isTrue();
        assertThat(items.get(0).has("video")).isFalse();
        assertThat(items.get(0).toString()).doesNotContain("secret-thumb", "secret-title", "Url");
        assertThat(json.writeValueAsString(items.get(1))).isEqualTo(json.writeValueAsString(text));
        assertThat(json.writeValueAsString(items.get(2))).isEqualTo(json.writeValueAsString(sticker));
    }

    @Test
    void newSendsAre201AndIdempotentReplaysAre200WithTheSameBodyShape() {
        MessageService service = mock(MessageService.class);
        MessageController controller = new MessageController(service);
        var request = com.tiktok.chatservice.dto.request.SendMessageRequest.video("123", "note", "key");
        var response = new MessageResponse("m", "c", 1L, "note", Instant.EPOCH, MessageType.VIDEO, null, null)
                .withVideo(new VideoCard("123", "AVAILABLE", 7L, "thumb", "title", 5));
        when(service.sendMessage("c", 1L, request)).thenReturn(response, response.asReplay());
        var first = controller.send(1L, "c", request);
        var retry = controller.send(1L, "c", request);
        assertThat(first.getStatusCode().value()).isEqualTo(201);
        assertThat(retry.getStatusCode().value()).isEqualTo(200);
        var json = JsonMapper.builder().findAndAddModules().build();
        assertThat(json.writeValueAsString(first.getBody().data()))
                .isEqualTo(json.writeValueAsString(retry.getBody().data()));
    }
}
