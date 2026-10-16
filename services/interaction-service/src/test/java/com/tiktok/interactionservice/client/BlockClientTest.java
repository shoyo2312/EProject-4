package com.tiktok.interactionservice.client;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class BlockClientTest {

    private final RestClient.Builder builder = RestClient.builder().baseUrl("http://user-service");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final BlockClient client = new BlockClient(builder.build());

    @Test
    void asksTheInternalEndpointAboutBothAccounts() {
        server.expect(requestTo("http://user-service/api/v1/users/internal/blocks?userA=2&userB=1"))
                .andRespond(withSuccess("""
                        {"success": true, "data": {"blocked": true}}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.isBlockedBetween(2L, 1L)).isTrue();
        server.verify();
    }

    /** On every reply: a user-service outage must not stop all of them. */
    @Test
    void failsOpen() {
        server.expect(requestTo("http://user-service/api/v1/users/internal/blocks?userA=2&userB=1"))
                .andRespond(withServerError());

        assertThat(client.isBlockedBetween(2L, 1L)).isFalse();
        server.verify();
    }

    /** Replying to yourself needs no question — nobody blocks themselves. */
    @Test
    void sameAccount_orMissingIds_askNothing() {
        assertThat(client.isBlockedBetween(1L, 1L)).isFalse();
        assertThat(client.isBlockedBetween(null, 1L)).isFalse();
        server.verify();
    }
}
