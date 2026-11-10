package com.tiktok.adminservice.exception;

import com.tiktok.common.exception.ResourceNotFoundException;

public class DeadLetterTopicNotFoundException extends ResourceNotFoundException {

    public DeadLetterTopicNotFoundException(String topic) {
        super("DEAD_LETTER_TOPIC_NOT_FOUND", "No dead-letter topic " + topic);
    }
}
