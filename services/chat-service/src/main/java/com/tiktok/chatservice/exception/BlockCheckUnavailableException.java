package com.tiktok.chatservice.exception;

import com.tiktok.common.exception.DomainException;
import org.springframework.http.HttpStatus;

public class BlockCheckUnavailableException extends DomainException {

    public BlockCheckUnavailableException(Throwable cause) {
        super("BLOCK_CHECK_UNAVAILABLE", "Messaging is temporarily unavailable, try again shortly",
                HttpStatus.SERVICE_UNAVAILABLE);
        initCause(cause);
    }
}
