package com.tiktok.userservice.entity;

public enum DataExportStatus {
    /** Waiting for, or being built by, {@code DataExportWorker}. */
    PROCESSING,
    /** The archive is in object storage and can be downloaded until {@code expiresAt}. */
    READY,
    /** The requester gave up on it while it was processing. */
    CANCELED,
    /** Building it kept failing; the requester can ask again. */
    FAILED,
    /** Past {@code expiresAt}, or replaced by a newer request. */
    EXPIRED
}
