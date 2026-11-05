package com.tiktok.apigateway.dto;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class MeResponseTest {

    private static final AuthMeResponse ACCOUNT = new AuthMeResponse(
            1L, "alice", "alice@example.com", "USER", "ACTIVE", true,
            Instant.parse("2024-01-01T00:00:00Z"), null, true, List.of());

    @Test
    void birthDatePassesThroughAndIsNullWithoutAProfile() {
        ProfileMeResponse profile = new ProfileMeResponse(
                1L, "Alice A", "hello", null, 10L, 5L, null, LocalDate.of(2001, 5, 9));

        assertThat(MeResponse.from(ACCOUNT, Optional.of(profile)).birthDate()).isEqualTo(LocalDate.of(2001, 5, 9));
        assertThat(MeResponse.from(ACCOUNT, Optional.empty()).birthDate()).isNull();
    }
}
