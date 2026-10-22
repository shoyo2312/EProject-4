package com.tiktok.chatservice.controller;

import com.tiktok.chatservice.config.SecurityConfig;
import com.tiktok.chatservice.dto.response.MessagePageResponse;
import com.tiktok.chatservice.repository.ConversationRepository;
import com.tiktok.chatservice.service.MessageService;
import com.tiktok.common.config.InternalApiAutoConfiguration;
import com.tiktok.common.http.InternalApiToken;
import com.tiktok.crypto.jwt.JwtProvider;
import com.tiktok.security.jwt.JwtSecurityAutoConfiguration;
import com.tiktok.security.jwt.RevokedTokenChecker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A user's chat history is for user-service's export job alone. Through the real security chain,
 * the internal token — and nothing else, whatever the method — opens the internal prefix.
 */
@WebMvcTest(controllers = InternalChatExportController.class)
@Import({SecurityConfig.class, JwtSecurityAutoConfiguration.class, InternalApiAutoConfiguration.class})
@TestPropertySource(properties = {"jwt.secret=" + InternalChatExportControllerTest.JWT_SECRET,
        "INTERNAL_API_TOKEN=internal-token-for-tests-0123456789abcdef"})
class InternalChatExportControllerTest {

    static final String JWT_SECRET = "test-secret-at-least-32-bytes-long-0123456789";

    private static final String MESSAGES = "/api/v1/conversations/internal/c1/messages";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InternalApiToken internalApiToken;

    @MockitoBean
    private MessageService messageService;

    @MockitoBean
    private ConversationRepository conversationRepository;

    /**
     * Redis is on this service's classpath, so the JWT filter gets the Redis revocation check, which
     * needs a StringRedisTemplate the slice does not provide. Nothing here is revoked.
     */
    @MockitoBean
    private RevokedTokenChecker revokedTokenChecker;

    private static String bearer(long userId) {
        return "Bearer " + new JwtProvider(JWT_SECRET).generateToken(String.valueOf(userId),
                Map.of(JwtProvider.CLAIM_TOKEN_TYPE, JwtProvider.TOKEN_TYPE_ACCESS), 60_000L);
    }

    @Test
    void messages_answerTheInternalToken() throws Exception {
        when(messageService.exportMessages("c1", null, 100))
                .thenReturn(new MessagePageResponse(List.of(), null, false));

        mockMvc.perform(get(MESSAGES).header(InternalApiToken.HEADER, internalApiToken.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hasMore").value(false));

        verify(messageService).exportMessages("c1", null, 100);
    }

    /** The prefix would otherwise fall to anyRequest().authenticated(), which admits any signed-in user. */
    @Test
    void messages_refuseACallerWithoutTheInternalToken() throws Exception {
        mockMvc.perform(get(MESSAGES)).andExpect(status().isForbidden());
        mockMvc.perform(get(MESSAGES).header("Authorization", bearer(9L))).andExpect(status().isForbidden());

        verify(messageService, never()).exportMessages(any(), any(), anyInt());
    }

    /**
     * Spring MVC answers a HEAD with the GET handler, so an internal rule for GET alone lets a HEAD
     * fall through to anyRequest().authenticated(): a user's JWT would page through anyone's messages.
     */
    @Test
    void messages_refuseAHeadWithAUsersJwt() throws Exception {
        mockMvc.perform(head(MESSAGES).header("Authorization", bearer(9L))).andExpect(status().isForbidden());

        verify(messageService, never()).exportMessages(any(), any(), anyInt());
    }
}
