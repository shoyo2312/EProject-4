package com.tiktok.authservice.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The poll token {@code POST /qr} handed the browser. */
public record QrLoginPollRequest(@NotBlank @Size(max = 128) String pollToken) {
}
