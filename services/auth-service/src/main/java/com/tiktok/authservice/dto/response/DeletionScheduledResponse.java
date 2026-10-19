package com.tiktok.authservice.dto.response;

import java.time.Instant;

/** @param deletionScheduledAt when the account is deleted unless its owner signs in again first */
public record DeletionScheduledResponse(Instant deletionScheduledAt) {
}
