package com.tiktok.userservice.exception;

import com.tiktok.common.exception.BadRequestException;

/**
 * A settings patch that is well-formed JSON but cannot be applied — an explicit null on a field
 * that has no null, or a comment keyword with nothing in it to match.
 *
 * <p>Carries {@code VALIDATION_ERROR} rather than a code of its own: to a client this is the same
 * class of mistake as a field failing its annotation, and the wording already says which field.
 * These checks cannot be annotations because they run against the patch merged over the stored
 * row, not against the request on its own.
 */
public class InvalidUserSettingsException extends BadRequestException {

    public InvalidUserSettingsException(String message) {
        super("VALIDATION_ERROR", message);
    }
}
