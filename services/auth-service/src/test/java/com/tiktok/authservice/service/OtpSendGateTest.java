package com.tiktok.authservice.service;

import com.tiktok.authservice.exception.TurnstileVerificationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What the gate does when Redis is unreachable: it falls back on Turnstile, and never lets a send
 * past it. The ticket against a working Redis is covered in {@link ResendTicketTest}.
 */
class OtpSendGateTest {

    private final TurnstileService turnstileService = mock(TurnstileService.class);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);

    private final OtpSendGate gate = new OtpSendGate(turnstileService, redisTemplate);

    @BeforeEach
    void redisIsDown() {
        RedisConnectionFailureException down = new RedisConnectionFailureException("connection refused");
        when(redisTemplate.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenThrow(down);
        doThrow(down).when(values).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void aTicketThatCannotBeChecked_needsTurnstile() {
        doThrow(new TurnstileVerificationException()).when(turnstileService).verify(null);

        assertThatThrownBy(() -> gate.admit("signup", "a@example.com", null, "some-ticket"))
                .as("a ticket nobody could read must not count as a valid one")
                .isInstanceOf(TurnstileVerificationException.class);
    }

    @Test
    void aSendThatPassedTurnstile_goesOutWithoutATicket() {
        assertThat(gate.admit("signup", "a@example.com", "solved", null))
                .as("the send must not fail because its ticket could not be stored")
                .isNull();

        verify(turnstileService).verify("solved");
    }
}
