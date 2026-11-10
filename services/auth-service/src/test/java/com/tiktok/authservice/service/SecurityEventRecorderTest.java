package com.tiktok.authservice.service;

import com.tiktok.authservice.entity.AuthSecurityEvent;
import com.tiktok.authservice.entity.SecurityEventType;
import com.tiktok.authservice.repository.AuthSecurityEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The recorder swallows every failure so a log can never block a sign-in — which also means a
 * value past its column silently drops the whole entry. These pin what reaches the row.
 */
@ExtendWith(MockitoExtension.class)
class SecurityEventRecorderTest {

    @Mock
    private AuthSecurityEventRepository repository;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private RequestOrigins requestOrigins;

    private SecurityEventRecorder recorder;

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        recorder = new SecurityEventRecorder(repository, transactionManager, requestOrigins);
    }

    private AuthSecurityEvent saved() {
        ArgumentCaptor<AuthSecurityEvent> captor = ArgumentCaptor.forClass(AuthSecurityEvent.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void thePlaceIsStoredWithTheEntry() {
        recorder.record(7L, SecurityEventType.LOGIN_FAILED, null,
                new RequestOrigin("81.2.69.160", null, null, null, null, "London, United Kingdom"));

        assertThat(saved())
                .extracting(AuthSecurityEvent::getIpAddress, AuthSecurityEvent::getLocation)
                .containsExactly("81.2.69.160", "London, United Kingdom");
    }

    @Test
    void aPlaceLongerThanTheColumnIsCut() {
        recorder.record(7L, SecurityEventType.LOGIN_FAILED, null,
                new RequestOrigin("81.2.69.160", null, null, null, null, "x".repeat(200)));

        assertThat(saved().getLocation()).hasSize(128);
    }

    @Test
    void theShortOverloadsReadTheCurrentRequest() {
        when(requestOrigins.current())
                .thenReturn(new RequestOrigin("81.2.69.160", null, null, null, null, "London, United Kingdom"));

        recorder.record(7L, SecurityEventType.LOGOUT);

        assertThat(saved().getLocation()).isEqualTo("London, United Kingdom");
    }
}
