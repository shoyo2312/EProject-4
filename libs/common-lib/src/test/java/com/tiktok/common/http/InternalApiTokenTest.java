package com.tiktok.common.http;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InternalApiTokenTest {

    private static final String TOKEN = "internal-token-for-tests-0123456789abcdef";
    private static final String NEXT = "next-internal-token-for-tests-0123456789";

    @Test
    void acceptsOnlyTheTokenAndTheRotatingOne() {
        InternalApiToken token = InternalApiToken.from(new MockEnvironment()
                .withProperty("INTERNAL_API_TOKEN", TOKEN)
                .withProperty("INTERNAL_API_TOKEN_ROTATING", NEXT));

        assertThat(token.value()).isEqualTo(TOKEN);
        assertThat(token.accepts(TOKEN)).isTrue();
        assertThat(token.accepts(NEXT)).isTrue();
        assertThat(token.accepts(TOKEN + "x")).isFalse();
        assertThat(token.accepts("")).isFalse();
        assertThat(token.accepts(null)).isFalse();
    }

    @Test
    void localAndTestRunsShareADefault() {
        MockEnvironment local = new MockEnvironment();
        local.setActiveProfiles("local");

        assertThat(InternalApiToken.from(local).value()).isEqualTo(InternalApiToken.LOCAL_DEFAULT);
    }

    @Test
    void anywhereElseAMissingWeakOrDefaultTokenStopsTheService() {
        assertThatThrownBy(() -> InternalApiToken.from(new MockEnvironment()))
                .hasMessageContaining("INTERNAL_API_TOKEN is not set");
        assertThatThrownBy(() -> InternalApiToken.from(new MockEnvironment()
                .withProperty("INTERNAL_API_TOKEN", "short")))
                .hasMessageContaining("at least 32 bytes");
        assertThatThrownBy(() -> InternalApiToken.from(new MockEnvironment()
                .withProperty("INTERNAL_API_TOKEN", InternalApiToken.LOCAL_DEFAULT)))
                .hasMessageContaining("local default");
        assertThatThrownBy(() -> InternalApiToken.from(new MockEnvironment()
                .withProperty("INTERNAL_API_TOKEN", TOKEN)
                .withProperty("INTERNAL_API_TOKEN_ROTATING", "short")))
                .hasMessageContaining("INTERNAL_API_TOKEN_ROTATING must be at least");
    }
}
