package com.tiktok.adminservice.exception;

import com.tiktok.common.exception.BadRequestException;

/** A replay writes wherever a record's headers say; only kafka-lib's DLTs hold records it put there. */
public class NotADeadLetterTopicException extends BadRequestException {

    public NotADeadLetterTopicException(String topic) {
        super("NOT_A_DEAD_LETTER_TOPIC", topic + " is not a dead-letter topic");
    }
}
