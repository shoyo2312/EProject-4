package com.tiktok.interactionservice.exception;

import com.tiktok.common.exception.BadRequestException;

/**
 * A comment-inbox order or filter that ranks the thread ({@code sort} other than NEWEST,
 * {@code replied}, {@code followers}) asked for without {@code videoId}. Ranking reads one video's
 * whole thread; across every video there is no such read, so the filter would be dropped and the
 * answer would look filtered while it is not.
 */
public class InboxFilterNeedsVideoException extends BadRequestException {

    public InboxFilterNeedsVideoException() {
        super("INBOX_FILTER_NEEDS_VIDEO", "sort, replied and followers need a videoId");
    }
}
