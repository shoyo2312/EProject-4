package com.tiktok.chatservice.dto.response;

/**
 * What the viewer may do in a conversation right now, decided on the server. The client renders
 * its composer from this and never infers it from message counts.
 */
public enum SendPermission {

    /** An ordinary conversation. */
    ALLOWED,

    /**
     * The viewer opened a request and may send its one message: either they have not sent it yet,
     * or {@code SendPolicy.RETRY_AFTER} has passed since the last one without an answer (or with a
     * deletion, which looks the same from this side) and they may send one more.
     */
    FIRST_MESSAGE_ONLY,

    /** The viewer's request message is waiting; {@code retryAvailableAt} says when they may send another. */
    REQUEST_SENT,

    /** The viewer is the recipient of a pending request and has to accept it before replying. */
    AWAITING_ACCEPT,

    /**
     * The viewer deleted this request. Opening the thread does nothing to it; sending a message
     * reopens it, which is what lets the other side reply — the client asks for confirmation first.
     */
    REOPEN_ON_SEND
}
