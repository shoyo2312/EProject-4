package com.tiktok.chatservice.dto.response;

/**
 * A chat picture as a client draws it. {@code url} is a path under the gateway that needs the viewer's
 * token: width and height are display dimensions (EXIF rotation applied), so space can be reserved
 * before the bytes arrive.
 */
public record ImageView(String imageId, String url, String mime, int width, int height, long size) {
}
