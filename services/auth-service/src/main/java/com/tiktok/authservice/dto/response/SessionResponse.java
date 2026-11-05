package com.tiktok.authservice.dto.response;

import com.tiktok.authservice.entity.DeviceType;

import java.time.Instant;

/**
 * One signed-in device, as its owner sees it under "Devices".
 *
 * @param deviceType   DESKTOP (laptop/PC) or MOBILE (phone/tablet) — the slot it occupies
 * @param deviceLabel  {@code "Chrome on Windows 11"}, {@code "Nowa app on Android 14 · SM-A525F"},
 *                     or null when the client named nothing known
 * @param deviceModel  the hardware model when the client sent one, or null
 * @param location     where the sign-in came from, {@code "Bien Hoa, Dong Nai, Vietnam"}, or null
 * @param signedInAt   when this device signed in
 * @param lastActiveAt when it last renewed its session
 * @param current      whether this is the device making the request
 */
public record SessionResponse(
        Long id,
        DeviceType deviceType,
        String deviceLabel,
        String deviceModel,
        String ipAddress,
        String location,
        Instant signedInAt,
        Instant lastActiveAt,
        boolean current
) {
}
