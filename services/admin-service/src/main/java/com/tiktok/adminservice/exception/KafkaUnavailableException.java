package com.tiktok.adminservice.exception;

import com.tiktok.common.exception.DomainException;
import org.springframework.http.HttpStatus;

/** The broker did not answer in time. Nothing was half-done that a retry cannot finish: see DeadLetterService. */
public class KafkaUnavailableException extends DomainException {

    public KafkaUnavailableException(Throwable cause) {
        super("KAFKA_UNAVAILABLE", "Kafka did not answer, please try again", HttpStatus.SERVICE_UNAVAILABLE);
        initCause(cause);
    }
}
