package com.tiktok.authservice.entity;

/** Why a session ended. Stored by name. */
public enum SessionEndReason {

    /** The device signed itself out. */
    LOGOUT,

    /** The owner signed this device out from another one. */
    REMOTE_LOGOUT,

    /** Another device of the same type signed in, and the limit is one per type. */
    REPLACED,

    /** Every session ended at once: password reset, ban, closure, or a detected token replay. */
    ALL
}
