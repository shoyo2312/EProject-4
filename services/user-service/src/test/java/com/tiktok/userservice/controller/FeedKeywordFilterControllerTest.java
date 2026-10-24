package com.tiktok.userservice.controller;

import com.tiktok.common.config.InternalApiAutoConfiguration;
import com.tiktok.crypto.jwt.JwtProvider;
import com.tiktok.security.jwt.JwtSecurityAutoConfiguration;
import com.tiktok.userservice.config.SecurityConfig;
import com.tiktok.userservice.dto.request.SaveKeywordFilterRequest;
import com.tiktok.userservice.dto.response.KeywordFilterResponse;
import com.tiktok.userservice.service.FeedKeywordFilterService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = FeedKeywordFilterController.class)
@Import({SecurityConfig.class, JwtSecurityAutoConfiguration.class, InternalApiAutoConfiguration.class})
@TestPropertySource(properties = {
        "jwt.secret=test-secret-at-least-32-bytes-long-0123456789",
        "INTERNAL_API_TOKEN=internal-token-for-tests-0123456789abcdef"
})
class FeedKeywordFilterControllerTest {

    private static final String JWT_SECRET = "test-secret-at-least-32-bytes-long-0123456789";
    private static final String PATH = "/api/v1/users/me/keyword-filters";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FeedKeywordFilterService service;

    private String bearer() {
        return "Bearer " + new JwtProvider(JWT_SECRET).generateToken("1",
                Map.of(JwtProvider.CLAIM_TOKEN_TYPE, JwtProvider.TOKEN_TYPE_ACCESS), 60_000L);
    }

    /** The shape the settings page lists. */
    @Test
    void list_answersTheCallersFilters() throws Exception {
        when(service.list(1L)).thenReturn(List.of(new KeywordFilterResponse(9L, "#cat", true, false, true)));

        mockMvc.perform(get(PATH).header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(9))
                .andExpect(jsonPath("$.data[0].keyword").value("#cat"))
                .andExpect(jsonPath("$.data[0].forYou").value(true))
                .andExpect(jsonPath("$.data[0].following").value(false))
                .andExpect(jsonPath("$.data[0].friends").value(true));
    }

    @Test
    void create_isCreatedForTheCaller() throws Exception {
        SaveKeywordFilterRequest request = new SaveKeywordFilterRequest("cat", true, false, true);
        when(service.create(1L, request)).thenReturn(new KeywordFilterResponse(9L, "cat", true, false, true));

        mockMvc.perform(post(PATH).header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keyword\": \"cat\", \"forYou\": true, \"following\": false, \"friends\": true}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(9));
    }

    @Test
    void create_withABlankKeyword_isAValidationError() throws Exception {
        mockMvc.perform(post(PATH).header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keyword\": \"  \", \"forYou\": true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verifyNoInteractions(service);
    }

    @Test
    void updateAndDelete_carryTheIdFromThePath() throws Exception {
        SaveKeywordFilterRequest request = new SaveKeywordFilterRequest("dog", false, true, false);
        when(service.update(1L, 9L, request)).thenReturn(new KeywordFilterResponse(9L, "dog", false, true, false));

        mockMvc.perform(put(PATH + "/9").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keyword\": \"dog\", \"forYou\": false, \"following\": true, \"friends\": false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.keyword").value("dog"));
        mockMvc.perform(delete(PATH + "/9").header("Authorization", bearer()))
                .andExpect(status().isOk());

        verify(service).delete(eq(1L), eq(9L));
    }
}
