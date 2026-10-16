package com.tiktok.adminservice.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AccountRoleClientTest {

    private MockRestServiceServer server;
    private AccountRoleClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://auth");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new AccountRoleClient(builder.build());

        // The console's request, whose token is the one forwarded.
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer console-token");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void anAdminAccount_isAnAdmin_askedWithTheConsolesOwnToken() {
        server.expect(requestTo("http://auth/api/v1/auth/admin/users/8"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer console-token"))
                .andRespond(withSuccess("{\"success\":true,\"data\":{\"id\":8,\"role\":\"ADMIN\"}}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.isAdmin(8L)).isTrue();
    }

    @Test
    void anOrdinaryAccount_isNot() {
        server.expect(requestTo("http://auth/api/v1/auth/admin/users/9"))
                .andRespond(withSuccess("{\"success\":true,\"data\":{\"id\":9,\"role\":\"USER\"}}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.isAdmin(9L)).isFalse();
    }

    /** No such account: the ban is a no-op downstream, as it always was. */
    @Test
    void noSuchAccount_isNot() {
        server.expect(requestTo("http://auth/api/v1/auth/admin/users/10"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.isAdmin(10L)).isFalse();
    }

    /** auth-service refuses an admin ban on its own side anyway; an outage of it holds up no ban. */
    @Test
    void authServiceUnreachable_failsOpen() {
        server.expect(requestTo("http://auth/api/v1/auth/admin/users/11"))
                .andRespond(withServerError());

        assertThat(client.isAdmin(11L)).isFalse();
    }
}
