package com.tiktok.userservice.event.consumer;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.tiktok.event.user.AccountState;
import com.tiktok.event.user.AccountStateChangedEvent;
import com.tiktok.userservice.repository.InboxEventRepository;
import com.tiktok.userservice.service.AccountStateService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountStateEventConsumerTest {

    @Mock
    private InboxEventRepository inboxEventRepository;

    @Mock
    private AccountStateService accountStateService;

    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();

    private AccountStateEventConsumer newConsumer() {
        return new AccountStateEventConsumer(inboxEventRepository, accountStateService, objectMapper);
    }

    @Test
    void deactivatedAndActive_areAssignmentsThatTakeNoClaim() throws Exception {
        AccountStateChangedEvent closed = AccountStateChangedEvent.of(1L, AccountState.DEACTIVATED, null);
        AccountStateChangedEvent reopened = AccountStateChangedEvent.of(1L, AccountState.ACTIVE, null);

        newConsumer().onMessage(objectMapper.writeValueAsString(closed));
        newConsumer().onMessage(objectMapper.writeValueAsString(reopened));

        verify(accountStateService).applyDeactivated(1L, true, closed.occurredAt());
        verify(accountStateService).applyDeactivated(1L, false, reopened.occurredAt());
        verifyNoInteractions(inboxEventRepository);
    }

    @Test
    void deleted_isClaimedBeforeTheAccountIsErased() throws Exception {
        AccountStateChangedEvent deleted = AccountStateChangedEvent.of(1L, AccountState.DELETED, null);
        when(inboxEventRepository.tryClaim(anyLong(), eq(deleted.eventId()), anyString(), any(Instant.class)))
                .thenReturn(1);

        newConsumer().onMessage(objectMapper.writeValueAsString(deleted));

        verify(accountStateService).erase(1L, deleted.occurredAt());
    }

    @Test
    void aRedeliveredDeletion_isSkipped() throws Exception {
        AccountStateChangedEvent deleted = AccountStateChangedEvent.of(1L, AccountState.DELETED, null);
        when(inboxEventRepository.tryClaim(anyLong(), eq(deleted.eventId()), anyString(), any(Instant.class)))
                .thenReturn(0);

        newConsumer().onMessage(objectMapper.writeValueAsString(deleted));

        verify(accountStateService, never()).erase(anyLong(), any());
    }
}
