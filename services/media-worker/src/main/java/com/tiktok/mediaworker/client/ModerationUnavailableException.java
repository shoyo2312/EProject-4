package com.tiktok.mediaworker.client;

/**
 * The check could not be completed — the service was unreachable, too slow, or answered with
 * something other than a verdict.
 *
 * <p>Distinct from any exception the sampling throws, because the two mean different things to
 * the caller: a video whose frames cannot be decoded is a fact about the video, while this is a
 * fact about our own infrastructure and says nothing at all about the content. Neither publishes
 * the video, but only this one is worth retrying.
 */
public class ModerationUnavailableException extends RuntimeException {

    public ModerationUnavailableException(String message) {
        super(message);
    }

    public ModerationUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
