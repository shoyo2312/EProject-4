package com.tiktok.userservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.user.AvatarMirroredEvent;
import com.tiktok.userservice.service.UserProfileService;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Puts our own copy of a provider picture onto the profile it belongs to.
 *
 * <p>No inbox claim here, unlike the registration consumer next door, and by reasoning rather than
 * oversight: the work is one conditional UPDATE to a fixed value, so a second delivery finds the
 * row already holding that value and changes nothing. The registration needs the claim because its
 * work is an INSERT, where a duplicate means a second profile.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AvatarMirroredEventConsumer {

    private final UserProfileService userProfileService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "media.avatar-events", groupId = "user-service")
    @Transactional
    @SneakyThrows
    public void onMessage(String payload) {
        AvatarMirroredEvent event = objectMapper.readValue(payload, AvatarMirroredEvent.class);

        userProfileService.applyMirroredAvatar(event.userId(), event.sourceUrl(), event.avatarUrl());
    }
}
