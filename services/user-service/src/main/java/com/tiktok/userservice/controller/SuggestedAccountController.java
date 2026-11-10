package com.tiktok.userservice.controller;

import com.tiktok.common.response.ApiResponse;
import com.tiktok.userservice.dto.response.SuggestedAccountResponse;
import com.tiktok.userservice.service.SuggestedAccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@Tag(name = "Suggestions", description = "People the caller may know")
public class SuggestedAccountController {

    private final SuggestedAccountService suggestedAccountService;

    @Operation(summary = "Suggested accounts for the caller",
            description = "Three tiers in order: people who follow the caller and are not followed "
                    + "back (newest first); people followed by the accounts the caller follows "
                    + "(most mutual followings first); then the most followed accounts as a top-up. "
                    + "Never the caller, anyone they follow or have a pending request to, anyone "
                    + "across a block, anyone they muted, or a closed/deleted account. "
                    + "limit defaults to 20 and is clamped to 1–50.")
    @GetMapping("/api/v1/users/me/suggested-accounts")
    public ApiResponse<List<SuggestedAccountResponse>> suggestedAccounts(
            @AuthenticationPrincipal Long currentUserId,
            @RequestParam(required = false) Integer limit) {
        return ApiResponse.success(suggestedAccountService.suggest(currentUserId, limit));
    }
}
