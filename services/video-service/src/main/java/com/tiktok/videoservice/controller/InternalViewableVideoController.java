package com.tiktok.videoservice.controller;

import com.tiktok.common.response.ApiResponse;
import com.tiktok.videoservice.dto.response.ViewableVideoResponse;
import com.tiktok.videoservice.service.VideoService;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

@RestController
@RequestMapping("/api/v1/videos/internal")
@RequiredArgsConstructor
@Validated
public class InternalViewableVideoController {
    private final VideoService videoService;

    @GetMapping("/viewable")
    public ApiResponse<List<ViewableVideoResponse>> viewable(@RequestParam Long viewerId,
            @RequestParam @NotEmpty @Size(max = 50) List<@NotBlank String> ids) {
        return ApiResponse.success(videoService.getViewable(viewerId, ids));
    }
}
