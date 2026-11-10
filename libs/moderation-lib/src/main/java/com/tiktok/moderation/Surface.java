package com.tiktok.moderation;

import java.util.Locale;

/**
 * Where a text is going to be shown. Most rules do not care; the ones that do are about links and
 * mentions, which mean something different in a comment than in a caption.
 */
public enum Surface {
    COMMENT,
    VIDEO_TITLE,
    VIDEO_DESCRIPTION,
    VIDEO_TAGS,
    /** Words the uploader laid over the picture itself in the app's editor. */
    VIDEO_OVERLAY,
    STORY_CAPTION,
    /** Words laid over a story's picture in the app's editor. */
    STORY_OVERLAY;

    /** A video's own text, written by its owner — as opposed to something said about it. */
    public boolean isCaption() {
        return this != COMMENT;
    }

    /** The value of the {@code surface} tag on the decision counter. */
    public String tag() {
        return name().toLowerCase(Locale.ROOT);
    }
}
