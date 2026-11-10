package com.tiktok.authservice.service;

import com.tiktok.authservice.repository.KnownDeviceRepository;
import com.tiktok.common.id.SnowflakeIdGenerator;
import com.tiktok.crypto.hash.HashUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Which sign-in tells the owner. The rule (spec §6.3):
 *
 * <ul>
 *   <li>a device the account already knows is quiet, and is touched;</li>
 *   <li>a new device is remembered, and alerts only if the account had a device before — so
 *       sign-up, and every account's first sign-in after this shipped, are quiet;</li>
 *   <li>no device id at all (the mobile app, a hand-made call) alerts only once the account has
 *       had a device — nothing says it is one seen before;</li>
 *   <li>a refresh remembers the device and never alerts, so a browser that was signed in before
 *       the deploy becomes known without signing in again.</li>
 * </ul>
 *
 * <p>"Had a device before" is the account's row in {@code known_device_accounts}, not the
 * presence of devices: devices age out after six months, and an account whose last one did would
 * otherwise read as brand new — making its takeover silent.
 *
 * <p>Every method joins the caller's transaction: a device must not be remembered by a sign-in
 * that rolls back, nor an alert lost after its device was.
 */
@Component
@RequiredArgsConstructor
public class KnownDevices {

    /** Every private window that signs in adds a row; the oldest beyond this are evicted. */
    static final int MAX_PER_ACCOUNT = 50;

    private enum Seen { KNOWN, FIRST, NEW }

    private final KnownDeviceRepository repository;

    /**
     * @param deviceId the client's id, already checked for format, or null when it sent none
     * @return whether the owner should be told about this sign-in
     */
    @Transactional
    public boolean signedIn(Long userId, String deviceId) {
        if (deviceId == null) {
            return repository.accountHasHadDevice(userId);
        }
        return remember(userId, deviceId) == Seen.NEW;
    }

    /** A refresh: remembers the device, and never asks for an alert. */
    @Transactional
    public void refreshed(Long userId, String deviceId) {
        if (deviceId != null) {
            remember(userId, deviceId);
        }
    }

    /** The account was erased. */
    @Transactional
    public void forget(Long userId) {
        repository.deleteAllByUserId(userId);
        repository.unmarkAccount(userId);
    }

    /**
     * The insert decides: a conflict means the device was known. A new one also marks the
     * account — the marker's own insert says whether it is the account's first device — and
     * evicts the least recently used beyond the bound.
     */
    private Seen remember(Long userId, String deviceId) {
        String hash = HashUtils.sha256(deviceId);
        Instant now = Instant.now();

        if (repository.insertIfAbsent(SnowflakeIdGenerator.nextId(), userId, hash, now) == 0) {
            repository.touch(userId, hash, now);
            return Seen.KNOWN;
        }

        boolean first = repository.markAccount(userId) == 1;
        repository.evictBeyond(userId, MAX_PER_ACCOUNT);
        return first ? Seen.FIRST : Seen.NEW;
    }
}
