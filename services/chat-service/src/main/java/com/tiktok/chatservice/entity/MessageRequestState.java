package com.tiktok.chatservice.entity;

/**
 * Where a conversation stands as a message request. A conversation with no state is an ordinary
 * one: every thread that existed before requests did, and every thread between mutual followers.
 * Accepting a request removes its state rather than setting a third value, so "no state" is the
 * single meaning of "both sides may chat".
 */
public enum MessageRequestState {

    /** Started by someone the recipient does not follow back; the initiator has one message to send. */
    PENDING,

    /** The recipient deleted the request. Kept, not removed, so the initiator cannot start it again. */
    DECLINED
}
