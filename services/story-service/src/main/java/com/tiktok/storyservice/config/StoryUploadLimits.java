package com.tiktok.storyservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Signed into the presigned POST policy, so MinIO refuses an oversize file before it lands. */
@ConfigurationProperties(prefix = "story.upload")
public record StoryUploadLimits(long imageMaxBytes, long videoMaxBytes) {
}
