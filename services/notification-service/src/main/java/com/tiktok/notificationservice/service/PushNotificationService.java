package com.tiktok.notificationservice.service;

public interface PushNotificationService {

    /**
     * Sends a push notification to a single device via FCM.
     *
     * @param fcmToken the recipient device's FCM registration token
     */
    void send(String fcmToken, String title, String body);
}
