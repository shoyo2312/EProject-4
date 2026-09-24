package com.tiktok.chatservice.exception;

import com.tiktok.common.exception.DomainException;
import org.springframework.http.HttpStatus;

/**
 * The recipient's DM audience could not be established, so the conversation does not open.
 *
 * <p><b>Fails closed</b>, exactly like {@link BlockCheckUnavailableException} right above it in
 * {@code ConversationServiceImpl}: chat already pauses rather than guess when it cannot reach
 * user-service, and a privacy setting is not the place to start guessing. Note this is the
 * opposite of interaction-service's comment audience, which fails open — a comment guard that
 * failed closed would stop every comment on the platform during a user-service outage, while a
 * DM guard that failed open would deliver messages somebody asked not to receive.
 *
 * <p>Also what an audience this service cannot name answers: that means a user-service deployed
 * ahead of this one, and a value chat does not understand is not a value it may ignore.
 */
public class DmSettingsUnavailableException extends DomainException {

    public DmSettingsUnavailableException(Throwable cause) {
        super("DM_SETTINGS_UNAVAILABLE", "Messaging is temporarily unavailable, try again shortly",
                HttpStatus.SERVICE_UNAVAILABLE);
        initCause(cause);
    }
}
