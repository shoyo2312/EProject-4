package com.tiktok.interactionservice.controller;

import com.tiktok.interactionservice.AbstractInteractionServiceIT;
import com.tiktok.interactionservice.service.LikeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class VideoCountsControllerTest extends AbstractInteractionServiceIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LikeService likeService;

    @Test
    void batch_returnsOneRowPerDistinctId_withoutAuthentication() throws Exception {
        likeService.like(50L, 1L);
        likeService.like(50L, 2L);
        likeService.like(51L, 1L);

        mockMvc.perform(get("/api/v1/interactions/videos/counts/batch")
                        .param("videoIds", "50,51,50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].videoId").value(50))
                .andExpect(jsonPath("$.data[0].likeCount").value(2))
                .andExpect(jsonPath("$.data[1].videoId").value(51))
                .andExpect(jsonPath("$.data[1].likeCount").value(1));
    }

    @Test
    void batch_capsTheNumberOfIdsItWillRead() throws Exception {
        String ids = IntStream.rangeClosed(1, 60)
                .mapToObj(i -> String.valueOf(1000 + i))
                .collect(Collectors.joining(","));

        mockMvc.perform(get("/api/v1/interactions/videos/counts/batch").param("videoIds", ids))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(50));
    }
}
