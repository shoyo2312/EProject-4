package com.tiktok.videoservice.dto.response;

import java.util.Map;

/**
 * @param uploadUrl        the bucket URL to POST the multipart form to — the bytes never pass
 *                         through this service
 * @param formFields       every field the multipart POST must carry (policy, signature, key,
 *                         Content-Type). The client appends these, then the file last.
 * @param fileUrl          what to send back as {@code rawFileUrl} on POST /api/v1/videos
 * @param expiresInSeconds how long the policy stays valid
 */
public record UploadUrlResponse(
        String uploadUrl,
        Map<String, String> formFields,
        String fileUrl,
        long expiresInSeconds
) {
}
