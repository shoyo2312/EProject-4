package com.tiktok.userservice.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * @param seconds  time spent since the last heartbeat; clamped by the service, not refused
 * @param timeZone the browser's IANA zone, e.g. {@code Asia/Ho_Chi_Minh}
 */
public record ScreenTimeHeartbeatRequest(
        @NotNull @Min(1) Integer seconds,
        @NotBlank @Size(max = 64) String timeZone
) {
}
