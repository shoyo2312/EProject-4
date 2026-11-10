package com.tiktok.storyservice.dto.response;

import java.util.Map;

/**
 * Same field names as video-service's UploadUrlResponse, so the web client's upload helper serves
 * both. {@code fileUrl} is the {@code s3://} location the create call echoes back.
 */
public record StoryUploadUrlResponse(
        String uploadUrl,
        Map<String, String> formFields,
        String fileUrl,
        long expiresInSeconds
) {
}
