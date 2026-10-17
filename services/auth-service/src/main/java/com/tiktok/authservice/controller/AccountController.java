package com.tiktok.authservice.controller;

import com.tiktok.authservice.dto.request.CloseAccountRequest;
import com.tiktok.authservice.dto.response.DeletionScheduledResponse;
import com.tiktok.authservice.service.AccountClosureService;
import com.tiktok.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in owner closing their own account. Reopening has no endpoint of its own: it is a
 * sign-in with {@code reactivate}, which checks the credential the way every sign-in does.
 */
@RestController
@RequestMapping("/api/v1/auth/account")
@RequiredArgsConstructor
@Tag(name = "Account", description = "Deactivating or deleting the signed-in account")
public class AccountController {

    private final AccountClosureService accountClosureService;

    @PostMapping("/closure-code")
    @Operation(summary = "Mail the code that confirms closing an account with no password",
            description = "403 EMAIL_NOT_VERIFIED when the account has no verified address, 429 "
                    + "TOO_MANY_OTP_REQUESTS past the send budget.")
    public ApiResponse<Void> sendClosureCode(@AuthenticationPrincipal Long userId) {
        accountClosureService.sendClosureCode(userId);
        return ApiResponse.success(null);
    }

    @PostMapping("/deactivate")
    @Operation(summary = "Deactivate the account until its owner signs in again",
            description = "Takes exactly one of password, otp, or provider with token. Every session "
                    + "ends. A wrong proof is 403 INVALID_CREDENTIALS / INVALID_SOCIAL_TOKEN (not 401: "
                    + "the session is fine) or 400 INVALID_OTP; 429 TOO_MANY_LOGIN_ATTEMPTS; 403 "
                    + "ACCOUNT_CLOSURE_NOT_ALLOWED for an admin account.")
    public ApiResponse<Void> deactivate(@AuthenticationPrincipal Long userId,
                                        @Valid @RequestBody CloseAccountRequest request) {
        accountClosureService.deactivate(userId, request);
        return ApiResponse.success(null);
    }

    @PostMapping("/delete")
    @Operation(summary = "Deactivate the account and delete it after the grace period",
            description = "Same body and answers as /deactivate. Signing in before "
                    + "deletionScheduledAt reopens the account and cancels the deletion.")
    public ApiResponse<DeletionScheduledResponse> delete(@AuthenticationPrincipal Long userId,
                                                         @Valid @RequestBody CloseAccountRequest request) {
        return ApiResponse.success(accountClosureService.requestDeletion(userId, request));
    }
}
