package com.tiktok.chatservice.client;

import com.tiktok.chatservice.exception.BlockCheckUnavailableException;
import com.tiktok.chatservice.exception.MessagingBlockedException;
import com.tiktok.common.http.InternalApiToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class BlockClientTest {

    private static final String URL = "http://user-service/api/v1/users/internal/blocks?userA=1&userB=2";
    private static final InternalApiToken TOKEN = InternalApiToken.from(new MockEnvironment()
            .withProperty("INTERNAL_API_TOKEN", "internal-token-for-tests-0123456789abcdef"));

    private MockRestServiceServer server;
    private BlockClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new BlockClient(builder, "http://user-service", TOKEN);
    }

    @Test
    void blockedPair_isRefused() {
        server.expect(requestTo(URL)).andExpect(header(InternalApiToken.HEADER, TOKEN.value())).andRespond(withSuccess(
                "{\"success\":true,\"data\":{\"blocked\":true}}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.requireNotBlocked(1L, 2L)).isInstanceOf(MessagingBlockedException.class);
    }

    @Test
    void unblockedPair_passes() {
        server.expect(requestTo(URL)).andRespond(withSuccess(
                "{\"success\":true,\"data\":{\"blocked\":false}}", MediaType.APPLICATION_JSON));

        assertThatCode(() -> client.requireNotBlocked(1L, 2L)).doesNotThrowAnyException();
    }

    /** Fails closed: a block that cannot be checked is not assumed absent. */
    @Test
    void userServiceDown_isAnErrorRatherThanAPass() {
        server.expect(requestTo(URL)).andRespond(withServerError());

        assertThatThrownBy(() -> client.requireNotBlocked(1L, 2L)).isInstanceOf(BlockCheckUnavailableException.class);
    }
}
