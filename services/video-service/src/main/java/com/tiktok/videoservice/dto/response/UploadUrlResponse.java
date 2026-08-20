package com.tiktok.videoservice.dto.response;

/**
 * @param uploadUrl         presigned PUT URL — the client uploads the file straight to object
 *                          storage with it, the bytes never pass through this service
 * @param fileUrl           what to send back as {@code rawFileUrl} on POST /api/v1/videos once
 *                          the upload succeeds
 * @param expiresInSeconds  how long {@code uploadUrl} stays valid
 */
public record UploadUrlResponse(
        String uploadUrl,
        String fileUrl,
        long expiresInSeconds
) {
}
