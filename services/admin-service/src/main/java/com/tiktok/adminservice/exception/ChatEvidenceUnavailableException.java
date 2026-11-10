package com.tiktok.adminservice.exception;

import com.tiktok.common.exception.DomainException;
import org.springframework.http.HttpStatus;

/** chat-service did not answer: no report is filed, because one without its evidence is worth nothing. */
public class ChatEvidenceUnavailableException extends DomainException {

    public ChatEvidenceUnavailableException(Throwable cause) {
        super("CHAT_EVIDENCE_UNAVAILABLE", "The conversation could not be read, please try again",
                HttpStatus.SERVICE_UNAVAILABLE);
        initCause(cause);
    }
}
