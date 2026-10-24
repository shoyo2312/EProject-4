package com.tiktok.userservice.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** Not four digits can never be right, and is refused rather than counted as a guess. */
public record VerifyPasscodeRequest(@NotNull @Pattern(regexp = "\\d{4}", message = "must be 4 digits") String passcode) {
}
