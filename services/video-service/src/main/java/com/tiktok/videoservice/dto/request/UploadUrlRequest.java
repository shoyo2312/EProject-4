package com.tiktok.videoservice.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param contentType MIME type of the file about to be uploaded. Decides the extension of the
 *                    stored object; unsupported types are refused here rather than after the
 *                    client has spent a minute uploading.
 */
public record UploadUrlRequest(
        @NotBlank @Size(max = 100) String contentType
) {
}
