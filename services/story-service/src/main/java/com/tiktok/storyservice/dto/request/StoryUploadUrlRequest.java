package com.tiktok.storyservice.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** @param contentType MIME type of the file about to be uploaded; decides the key's extension. */
public record StoryUploadUrlRequest(@NotBlank @Size(max = 100) String contentType) {
}
