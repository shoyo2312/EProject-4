package com.tiktok.userservice.event.producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.userservice.entity.OutboxEvent;
import com.tiktok.userservice.repository.OutboxEventRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class UserEventPublisherTest {

    private final OutboxEventRepository outboxEventRepository = mock(OutboxEventRepository.class);
    private final UserEventPublisher publisher =
            new UserEventPublisher(outboxEventRepository, new ObjectMapper().findAndRegisterModules());

    /**
     * The aggregate type is what OutboxPublisher routes on and the aggregate id is the record key,
     * so a wrong one here is an event on the wrong topic, or out of order on the right one.
     */
    @Test
    void eachAnnouncement_isQueuedUnderItsAggregateType_andKeyedByWhoItConcerns() {
        publisher.publishFollowChanged(1L, 2L, true, true);
        publisher.publishFollowRequested(3L, 4L);
        publisher.publishPrivacyChanged(5L, true);

        ArgumentCaptor<OutboxEvent> rows = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository, times(3)).save(rows.capture());
        assertThat(rows.getAllValues())
                .extracting(OutboxEvent::getAggregateType, OutboxEvent::getAggregateId, OutboxEvent::getEventType)
                .containsExactly(
                        tuple("USER_FOLLOW", "2", "UserFollowChangedEvent"),
                        tuple("FOLLOW_REQUEST", "4", "FollowRequestedEvent"),
                        tuple("USER_PRIVACY", "5", "UserPrivacyChangedEvent"));
        assertThat(rows.getAllValues().get(0).getPayload()).contains("\"requestApproved\":true");
        assertThat(rows.getAllValues().get(1).getPayload()).contains("\"requesterId\":3", "\"targetId\":4");
        assertThat(rows.getAllValues().get(2).getPayload()).contains("\"userId\":5", "\"privateAccount\":true");
    }

    @Test
    void aPlainFollow_isNotAnApprovedRequest() {
        publisher.publishFollowChanged(1L, 2L, true);

        ArgumentCaptor<OutboxEvent> row = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(row.capture());
        assertThat(row.getValue().getPayload()).contains("\"followed\":true", "\"requestApproved\":false");
    }
}
