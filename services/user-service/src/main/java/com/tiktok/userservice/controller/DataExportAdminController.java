package com.tiktok.userservice.controller;

import com.tiktok.common.response.ApiResponse;
import com.tiktok.userservice.dto.response.DataExportAdminSummaryResponse;
import com.tiktok.userservice.service.DataExportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Under /users/admin, which SecurityConfig pins to ROLE_ADMIN. */
@RestController
@RequestMapping("/api/v1/users/admin/data-exports")
@RequiredArgsConstructor
@Tag(name = "Download your data (admin)", description = "Watch the archive builds platform-wide")
public class DataExportAdminController {

    private final DataExportService dataExportService;

    @Operation(summary = "Admin: requests processing, stalled, ready and failed",
            description = "ROLE_ADMIN only. Failures are counted over the last 7 days, with the 20 "
                    + "latest listed. Erased accounts are left out.")
    @GetMapping("/summary")
    public ApiResponse<DataExportAdminSummaryResponse> summary() {
        return ApiResponse.success(dataExportService.adminSummary());
    }
}
