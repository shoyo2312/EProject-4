package com.tiktok.authservice.controller;

import com.tiktok.authservice.dto.request.QrLoginPollRequest;
import com.tiktok.authservice.dto.response.QrLoginCreatedResponse;
import com.tiktok.authservice.dto.response.QrLoginScanResponse;
import com.tiktok.authservice.dto.response.QrLoginStatusResponse;
import com.tiktok.authservice.service.QrLoginService;
import com.tiktok.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * QR sign-in: the browser asks for a code and polls it (no session needed — that is the point),
 * the signed-in phone scans it and approves. See {@link QrLoginService}.
 */
@RestController
@RequestMapping("/api/v1/auth/qr")
@RequiredArgsConstructor
@Tag(name = "QR sign-in", description = "Sign a browser in by scanning a code with the signed-in app")
public class QrLoginController {

    private final QrLoginService qrLoginService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Browser: get a code to show",
            description = "Encode qrContent in the image; keep pollToken in the page only. The code "
                    + "lives 2 minutes. 429 TOO_MANY_QR_LOGIN_REQUESTS past 30 codes per address in "
                    + "10 minutes.")
    public ApiResponse<QrLoginCreatedResponse> create() {
        return ApiResponse.success(qrLoginService.create());
    }

    @PostMapping("/{qrId}/status")
    @Operation(summary = "Browser: poll the code",
            description = "PENDING → SCANNED (with the username) → APPROVED (with tokens, answered "
                    + "once) or REJECTED. EXPIRED for a timed-out, spent or unknown code, and for a "
                    + "wrong pollToken. Poll every 2 seconds.")
    public ApiResponse<QrLoginStatusResponse> poll(@PathVariable String qrId,
                                                   @Valid @RequestBody QrLoginPollRequest request) {
        return ApiResponse.success(qrLoginService.poll(qrId, request.pollToken()));
    }

    @PostMapping("/{qrId}/scan")
    @Operation(summary = "Phone: claim a scanned code",
            description = "Answers which browser is asking, for the confirm screen. 410 "
                    + "QR_LOGIN_EXPIRED, 409 QR_LOGIN_ALREADY_SCANNED by another account, 403 "
                    + "QR_LOGIN_NOT_ALLOWED for an admin or an account that is not open.")
    public ApiResponse<QrLoginScanResponse> scan(@PathVariable String qrId,
                                                 @AuthenticationPrincipal Long userId) {
        return ApiResponse.success(qrLoginService.scan(qrId, userId));
    }

    @PostMapping("/{qrId}/approve")
    @Operation(summary = "Phone: sign the browser in",
            description = "Only after /scan from the same account. 410 QR_LOGIN_EXPIRED otherwise.")
    public ApiResponse<Void> approve(@PathVariable String qrId, @AuthenticationPrincipal Long userId) {
        qrLoginService.approve(qrId, userId);
        return ApiResponse.success(null);
    }

    @PostMapping("/{qrId}/reject")
    @Operation(summary = "Phone: refuse the browser",
            description = "Only after /scan from the same account. 410 QR_LOGIN_EXPIRED otherwise.")
    public ApiResponse<Void> reject(@PathVariable String qrId, @AuthenticationPrincipal Long userId) {
        qrLoginService.reject(qrId, userId);
        return ApiResponse.success(null);
    }
}
