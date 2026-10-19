package com.tiktok.notificationservice.service;

import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.tiktok.crypto.hash.HashUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class FcmPushNotificationServiceTest {

    @Test
    void aFailedPush_logsAFingerprintOfTheDevice_neverItsToken(CapturedOutput output) throws Exception {
        FirebaseMessaging firebaseMessaging = mock(FirebaseMessaging.class);
        when(firebaseMessaging.send(any(Message.class))).thenThrow(mock(FirebaseMessagingException.class));
        String token = "dQw4w9WgXcQ:APA91bHun4MxP5egoKMwt2KZFBaFUH-1RYqx";

        new FcmPushNotificationService(firebaseMessaging).send(token, "New follower", "someone followed you");

        assertThat(output.getOut() + output.getErr())
                .contains("Failed to deliver FCM push")
                .contains(HashUtils.sha256(token).substring(0, 12))
                .doesNotContain(token);
    }
}
