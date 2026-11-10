package com.tiktok.notificationservice.event.consumer;

import com.tiktok.event.user.WeeklyScreenTimeSummaryEvent;
import com.tiktok.notificationservice.entity.NotificationType;
import com.tiktok.notificationservice.repository.ProcessedEventRepository;
import com.tiktok.notificationservice.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScreenTimeSummaryEventConsumerTest {

    @Mock
    private NotificationService notificationService;

    @Mock
    private ProcessedEventRepository processedEventRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ScreenTimeSummaryEventConsumer consumer() {
        return new ScreenTimeSummaryEventConsumer(
                new IdempotentEventProcessor(processedEventRepository), notificationService, objectMapper, "Nowaa");
    }

    private void deliver(WeeklyScreenTimeSummaryEvent event, boolean firstTime) {
        when(processedEventRepository.tryClaim(eq(event.eventId()), anyString())).thenReturn(firstTime);
        consumer().onMessage(objectMapper.writeValueAsString(event));
    }

    /** 12000s is 3h 20m; a seventh of it is 28m 34s, shown as 28m. The week's Monday is the reference. */
    @Test
    void summary_writesOneInboxEntry() {
        deliver(WeeklyScreenTimeSummaryEvent.of(1L, "2026-10-19", 12000), true);

        verify(notificationService).create(1L, null, NotificationType.SCREEN_TIME, "Your weekly screen time",
                "Last week you spent 3h 20m on Nowaa, a daily average of 28m.", "2026-10-19");
    }

    @Test
    void summary_underAnHourHasNoHours() {
        deliver(WeeklyScreenTimeSummaryEvent.of(1L, "2026-10-19", 1500), true);

        verify(notificationService).create(1L, null, NotificationType.SCREEN_TIME, "Your weekly screen time",
                "Last week you spent 25m on Nowaa, a daily average of 3m.", "2026-10-19");
    }

    /** An outbox re-send carries the same eventId: one entry, not two. */
    @Test
    void summary_aRedeliveryWritesNothing() {
        deliver(WeeklyScreenTimeSummaryEvent.of(1L, "2026-10-19", 12000), false);

        verify(notificationService, never()).create(any(), any(), any(), any(), any(), any());
    }
}
