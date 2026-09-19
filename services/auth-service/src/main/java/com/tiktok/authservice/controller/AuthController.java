package com.tiktok.authservice.controller;

import com.tiktok.authservice.dto.request.AddEmailRequest;
import com.tiktok.authservice.dto.request.AdminLoginOtpRequest;
import com.tiktok.authservice.dto.request.ForgotPasswordRequest;
import com.tiktok.authservice.dto.request.LoginRequest;
import com.tiktok.authservice.dto.request.RefreshTokenRequest;
import com.tiktok.authservice.dto.request.RegisterRequest;
import com.tiktok.authservice.dto.request.ResendVerificationRequest;
import com.tiktok.authservice.dto.request.ResetPasswordRequest;
import com.tiktok.authservice.dto.request.VerifyEmailRequest;
import com.tiktok.authservice.dto.response.TokenResponse;
import com.tiktok.authservice.dto.response.UserResponse;
import com.tiktok.authservice.service.AuthService;
import com.tiktok.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "Registration, sign-in, token lifecycle, and the email flows")
public class AuthController {

    private static final String BEARER_PREFIX = "Bearer ";

    private final AuthService authService;

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a password account",
            description = "Always a USER; the account is created unverified and an OTP is mailed. "
                    + "409 USERNAME_ALREADY_EXISTS / EMAIL_ALREADY_EXISTS on a taken identity.")
    public ApiResponse<UserResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResponse.success(authService.register(request));
    }

    @PostMapping("/login")
    @Operation(summary = "Sign in with a password",
            description = "401 INVALID_CREDENTIALS, 403 EMAIL_NOT_VERIFIED when the address is "
                    + "unconfirmed, 429 TOO_MANY_LOGIN_ATTEMPTS, and for an admin account "
                    + "401 MFA_REQUIRED — an OTP has been mailed, continue at /login/otp.")
    public ApiResponse<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.success(authService.login(request));
    }

    /**
     * Second step of admin-console login. Reached only after {@code /login} answered
     * {@code MFA_REQUIRED}; exchanges the emailed code for a session.
     */
    @PostMapping("/login/otp")
    @Operation(summary = "Finish an admin sign-in with the emailed code",
            description = "Only after /login answered MFA_REQUIRED. 401 INVALID_OTP on a wrong or "
                    + "expired code, 429 TOO_MANY_OTP_REQUESTS after repeated guesses.")
    public ApiResponse<TokenResponse> loginWithOtp(@Valid @RequestBody AdminLoginOtpRequest request) {
        return ApiResponse.success(authService.loginWithOtp(request));
    }

    @PostMapping("/refresh")
    @Operation(summary = "Rotate a refresh token into a new session",
            description = "The presented token is spent. Presenting an already-rotated one is "
                    + "401 INVALID_REFRESH_TOKEN, and outside the rotation grace window it is "
                    + "treated as theft: every session for that user is revoked.")
    public ApiResponse<TokenResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return ApiResponse.success(authService.refresh(request));
    }

    /**
     * Both halves are optional and the endpoint is idempotent: a client that still holds an access
     * token but has lost its refresh token has an access token worth blacklisting, and one whose
     * access token expired an hour ago still has a session row to revoke. Requiring the body meant
     * the first case could not log out at all, and its access token stayed live for its full
     * fifteen minutes.
     */
    @PostMapping("/logout")
    @Operation(summary = "End a session",
            description = "Revokes the refresh token and blacklists the presented access token. "
                    + "Both are optional and an unknown token is not an error — logout is "
                    + "idempotent, and saying otherwise would tell a caller which tokens are live.")
    public ApiResponse<Void> logout(@RequestBody(required = false) RefreshTokenRequest request,
                                    @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader) {
        String accessToken = authorizationHeader != null && authorizationHeader.startsWith(BEARER_PREFIX)
                ? authorizationHeader.substring(BEARER_PREFIX.length())
                : null;
        authService.logout(request == null ? null : request.refreshToken(), accessToken);
        return ApiResponse.success(null);
    }

    @GetMapping("/me")
    @Operation(summary = "The signed-in account")
    public ApiResponse<UserResponse> me(@AuthenticationPrincipal Long userId) {
        return ApiResponse.success(authService.getCurrentUser(userId));
    }

    /**
     * Authenticated, unlike the rest of the email flow: the account being given an address is the
     * one holding the token, and there is no address yet to name it by.
     */
    @PostMapping("/email")
    @Operation(summary = "Give a social-only account an email address",
            description = "Authenticated. 409 EMAIL_ALREADY_LINKED once an address is verified — "
                    + "replacing a confirmed address is how an account gets stolen.")
    public ApiResponse<Void> addEmail(@AuthenticationPrincipal Long userId,
                                      @Valid @RequestBody AddEmailRequest request) {
        authService.addEmail(userId, request);
        return ApiResponse.success(null);
    }

    @PostMapping("/verify-email")
    @Operation(summary = "Confirm an address with the emailed code",
            description = "401 INVALID_OTP covers both a wrong code and an expired one.")
    public ApiResponse<Void> verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
        authService.verifyEmail(request);
        return ApiResponse.success(null);
    }

    @PostMapping("/resend-verification")
    @Operation(summary = "Mail the verification code again",
            description = "Silent about whether the address exists or is already verified. "
                    + "429 TOO_MANY_OTP_REQUESTS past the send budget.")
    public ApiResponse<Void> resendVerification(@Valid @RequestBody ResendVerificationRequest request) {
        authService.resendVerification(request);
        return ApiResponse.success(null);
    }

    @PostMapping("/forgot-password")
    @Operation(summary = "Mail a password-reset code",
            description = "Silent about whether the address exists. 429 TOO_MANY_OTP_REQUESTS "
                    + "past the send budget.")
    public ApiResponse<Void> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        authService.forgotPassword(request);
        return ApiResponse.success(null);
    }

    @PostMapping("/reset-password")
    @Operation(summary = "Set a new password with the emailed code",
            description = "Every session for the account is revoked on success — the reset is "
                    + "usually done because the account is suspected stolen.")
    public ApiResponse<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request);
        return ApiResponse.success(null);
    }
}
