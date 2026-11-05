package com.tiktok.videoservice.exception;

import com.tiktok.common.exception.BadRequestException;

/**
 * The raw file being published sits under someone else's upload prefix.
 *
 * <p>400 rather than 403: the request is malformed against a contract the client controls
 * entirely — it publishes what it just uploaded — and a 403 would confirm that the key belongs
 * to a real account, which is exactly what someone probing for other people's uploads wants to
 * learn. The offending key is not echoed back for the same reason.
 */
public class ForeignUploadException extends BadRequestException {

    public ForeignUploadException() {
        this("rawFileUrl");
    }

    /** @param field the request field holding the URL, named in the message */
    public ForeignUploadException(String field) {
        super("FOREIGN_UPLOAD", "%s must point at a file you uploaded".formatted(field));
    }
}
