package com.tiktok.videoservice.dto.request;

import com.tiktok.videoservice.entity.VideoStatus;
import com.tiktok.videoservice.entity.VideoVisibility;

/**
 * TikTok Studio's Posts table: which of the owner's own videos, in what order. Every filter is
 * optional; the owner sees every status, as on their own profile grid.
 *
 * @param status     only videos in this status
 * @param visibility only videos with this audience
 * @param q          case-insensitive substring of the title or the description
 * @param sort       null for newest first
 */
public record StudioPostFilter(VideoStatus status, VideoVisibility visibility, String q, Sort sort) {

    public enum Sort {
        NEWEST, VIEWS, LIKES, COMMENTS
    }
}
