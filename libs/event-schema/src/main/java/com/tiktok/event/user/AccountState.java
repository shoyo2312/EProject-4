package com.tiktok.event.user;

/** Where an account stands after its owner closed it, reopened it, or its deletion ran. */
public enum AccountState {
    ACTIVE,
    DEACTIVATED,
    DELETED
}
