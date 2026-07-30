package com.tiktok.notificationservice.service;

import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.Notification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "firebase", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class FcmPushNotificationService implements PushNotificationService {

    private final FirebaseMessaging firebaseMessaging;

    @Override
    public void send(String fcmToken, String title, String body) {
        Message message = Message.builder()
                .setToken(fcmToken)
                .setNotification(Notification.builder()
                        .setTitle(title)
                        .setBody(body)
                        .build())
                .build();

        try {
            firebaseMessaging.send(message);
        } catch (FirebaseMessagingException e) {
            log.warn("Failed to deliver FCM push to token {}: {}", fcmToken, e.getMessage());
        }
    }
}
