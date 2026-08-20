package com.tiktok.videoservice.dto.request;

import com.tiktok.common.validation.ValidMediaUrl;
import com.tiktok.videoservice.entity.VideoVisibility;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateVideoRequest(
        @NotBlank @Size(max = 150) String title,
        @Size(max = 2000) String description,
        // Goes straight into VideoPublishedEvent, which media-worker consumes and fetches. An
        // unchecked value here is a URL the client picks and our own backend then requests, so
        // it must resolve to our object storage: an s3:// URI in an allowed bucket, or an https
        // URL on an allowed host. See app.media in application.yml.
        @NotBlank @Size(max = 500) @ValidMediaUrl(schemes = {"https", "s3"}) String rawFileUrl,
        @NotNull VideoVisibility visibility,
        // Optional. Capped because the list is indexed and rides on every VideoPublishedEvent, and
        // an uncapped tag list is the cheapest way for one uploader to bloat both. Normalised
        // server-side, so "#Dance" and "dance " are the same tag by the time anything reads them.
        @Size(max = 10) List<@NotBlank @Size(max = 50) String> tags
) {
}
