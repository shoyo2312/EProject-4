package com.tiktok.interactionservice.exception;

import com.tiktok.common.exception.DomainException;
import org.springframework.http.HttpStatus;

/**
 * The broker did not take the event that goes with an interaction, so the interaction was undone
 * (see InteractionEventPublisher). Nothing the caller sent was wrong and nothing of it was kept,
 * which is what makes it worth retrying — and what a bare 500 would not have told the client.
 * The publisher logs the cause; it is never returned, because it names topics and brokers.
 */
public class EventNotPublishedException extends DomainException {

    public EventNotPublishedException(Throwable cause) {
        super("INTERACTION_NOT_RECORDED", "Could not record that right now, please try again",
                HttpStatus.SERVICE_UNAVAILABLE);
        initCause(cause);
    }
}
