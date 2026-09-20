package com.tiktok.notificationservice.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Stands in when Firebase is off, which is the default and the whole of local development.
 *
 * <p>The condition is the exact complement of {@link FcmPushNotificationService}'s, so exactly
 * one of the two exists. {@code @ConditionalOnMissingBean} would read better but is unreliable
 * outside auto-configuration: it is evaluated against whatever has been registered so far, and
 * both beans here come from the same component scan.
 *
 * <p>The inbox entry is written either way — a push is the nudge, not the notification.
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "firebase", name = "enabled", havingValue = "false", matchIfMissing = true)
public class NoOpPushNotificationService implements PushNotificationService {

    @Override
    public void send(String fcmToken, String title, String body) {
        log.debug("Firebase disabled, not pushing \"{}\"", title);
    }
}
