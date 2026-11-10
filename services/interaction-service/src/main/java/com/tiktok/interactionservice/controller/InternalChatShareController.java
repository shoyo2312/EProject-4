package com.tiktok.interactionservice.controller;

import com.tiktok.common.response.ApiResponse;
import com.tiktok.interactionservice.service.ChatShareService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/interactions/internal/videos/{videoId}/shares")
@RequiredArgsConstructor
public class InternalChatShareController {
    private final ChatShareService shareService;

    @PostMapping
    public ApiResponse<Counted> count(@PathVariable Long videoId, @Valid @RequestBody ShareRequest request) {
        return ApiResponse.success(new Counted(shareService.count(videoId, request.userId(), request.dedupeKey())));
    }

    public record ShareRequest(@NotNull Long userId, @NotBlank @Size(max = 64) String dedupeKey) {}
    public record Counted(boolean counted) {}
}
