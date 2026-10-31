package com.tiktok.notificationservice.event.consumer;

import com.tiktok.event.user.WeeklyScreenTimeSummaryEvent;
import com.tiktok.notificationservice.entity.NotificationType;
import com.tiktok.notificationservice.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Settings → Screen time → Weekly screen time updates: user-service's weekly summary becomes one
 * inbox entry. {@link NotificationType#SCREEN_TIME} is never pushed (see PushPreferences), and the
 * web shows no banner for it.
 */
@Component
@RequiredArgsConstructor
public class ScreenTimeSummaryEventConsumer {

    private final IdempotentEventProcessor idempotentEventProcessor;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    /** The name the welcome note is signed with too: auth-service reads the same variable. */
    @Value("${MAIL_BRAND_NAME:Nowaa}")
    private final String brandName;

    @KafkaListener(topics = "user.screen-time-summary-events", groupId = "notification-service")
    @SneakyThrows
    public void onMessage(String payload) {
        WeeklyScreenTimeSummaryEvent event = objectMapper.readValue(payload, WeeklyScreenTimeSummaryEvent.class);
        long total = event.totalSeconds();

        idempotentEventProcessor.runOnce(event.eventId(), event.getClass().getSimpleName(), () ->
                notificationService.create(event.userId(), null, NotificationType.SCREEN_TIME,
                        "Your weekly screen time",
                        "Last week you spent " + duration(total) + " on " + brandName
                                + ", a daily average of " + duration(total / 7) + ".",
                        event.weekStart()));
    }

    /** "3h 20m", or "25m" under an hour; whole minutes, rounded down. */
    static String duration(long seconds) {
        long minutes = seconds / 60;
        return minutes < 60 ? minutes + "m" : (minutes / 60) + "h " + (minutes % 60) + "m";
    }
}
