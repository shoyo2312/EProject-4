package com.tiktok.userservice.controller;

import com.tiktok.common.response.ApiResponse;
import com.tiktok.userservice.dto.request.SaveKeywordFilterRequest;
import com.tiktok.userservice.dto.response.KeywordFilterResponse;
import com.tiktok.userservice.service.FeedKeywordFilterService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/users/me/keyword-filters")
@RequiredArgsConstructor
@Tag(name = "Keyword filters", description = "Settings → Content preferences → Filter keywords")
public class FeedKeywordFilterController {

    private final FeedKeywordFilterService service;

    @Operation(summary = "The caller's keyword filters, newest first")
    @GetMapping
    public ApiResponse<List<KeywordFilterResponse>> list(@AuthenticationPrincipal Long currentUserId) {
        return ApiResponse.success(service.list(currentUserId));
    }

    @Operation(summary = "Filter a keyword from some feeds",
            description = "One word or one hashtag, at most 200 characters, and at least one of forYou, "
                    + "following, friends. Matching videos leave those feeds within a few seconds. "
                    + "400 VALIDATION_ERROR, 409 KEYWORD_FILTER_DUPLICATE (any capitalization), "
                    + "409 TOO_MANY_KEYWORD_FILTERS past 100.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<KeywordFilterResponse> create(@AuthenticationPrincipal Long currentUserId,
                                                     @Valid @RequestBody SaveKeywordFilterRequest request) {
        return ApiResponse.success(service.create(currentUserId, request));
    }

    @Operation(summary = "Replace a keyword filter",
            description = "Same rules as creating one. 404 KEYWORD_FILTER_NOT_FOUND for an id the caller does not own.")
    @PutMapping("/{id}")
    public ApiResponse<KeywordFilterResponse> update(@AuthenticationPrincipal Long currentUserId,
                                                     @PathVariable Long id,
                                                     @Valid @RequestBody SaveKeywordFilterRequest request) {
        return ApiResponse.success(service.update(currentUserId, id, request));
    }

    @Operation(summary = "Stop filtering a keyword", description = "404 KEYWORD_FILTER_NOT_FOUND for an id the caller does not own.")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@AuthenticationPrincipal Long currentUserId, @PathVariable Long id) {
        service.delete(currentUserId, id);
        return ApiResponse.success(null);
    }
}
