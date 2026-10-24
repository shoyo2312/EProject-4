package com.tiktok.videoservice.dto.request;

/**
 * Which of the viewer's feeds a read is for, so the matching keyword list of Settings → Filter
 * keywords applies. Sent by the web on the For You, Following and Friends reads only; a read
 * without it (a profile, a share link) is not filtered.
 */
public enum FeedKind {
    FOR_YOU,
    FOLLOWING,
    FRIENDS
}
