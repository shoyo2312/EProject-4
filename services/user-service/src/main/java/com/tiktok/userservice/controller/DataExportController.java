package com.tiktok.userservice.controller;

import com.tiktok.common.response.ApiResponse;
import com.tiktok.userservice.dto.request.RequestDataExportRequest;
import com.tiktok.userservice.dto.response.DataExportDownloadResponse;
import com.tiktok.userservice.dto.response.DataExportResponse;
import com.tiktok.userservice.service.DataExportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/users/me/data-exports")
@RequiredArgsConstructor
@Tag(name = "Download your data", description = "Request, track and download an archive of your data")
public class DataExportController {

    private final DataExportService dataExportService;

    @Operation(summary = "The caller's latest data requests, newest first")
    @GetMapping
    public ApiResponse<List<DataExportResponse>> list(@AuthenticationPrincipal Long currentUserId) {
        return ApiResponse.success(dataExportService.list(currentUserId));
    }

    @Operation(summary = "Request a copy of your data",
            description = "Built in the background; poll the list for READY. A file already ready "
                    + "expires. 400 VALIDATION_ERROR, 409 DATA_EXPORT_IN_PROGRESS.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<DataExportResponse> request(@AuthenticationPrincipal Long currentUserId,
                                                   @Valid @RequestBody RequestDataExportRequest request) {
        return ApiResponse.success(dataExportService.request(currentUserId, request));
    }

    @Operation(summary = "Cancel a request still processing",
            description = "404 DATA_EXPORT_NOT_FOUND, 409 DATA_EXPORT_NOT_CANCELABLE.")
    @PostMapping("/{requestId}/cancel")
    public ApiResponse<DataExportResponse> cancel(@AuthenticationPrincipal Long currentUserId,
                                                  @PathVariable Long requestId) {
        return ApiResponse.success(dataExportService.cancel(currentUserId, requestId));
    }

    @Operation(summary = "A short-lived link to a ready file",
            description = "404 DATA_EXPORT_NOT_FOUND, 409 DATA_EXPORT_UNAVAILABLE when not ready or expired.")
    @GetMapping("/{requestId}/download-url")
    public ApiResponse<DataExportDownloadResponse> downloadUrl(@AuthenticationPrincipal Long currentUserId,
                                                               @PathVariable Long requestId) {
        return ApiResponse.success(dataExportService.downloadLink(currentUserId, requestId));
    }
}
