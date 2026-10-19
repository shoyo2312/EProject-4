package com.tiktok.interactionservice.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds the comment limits read by {@code CommentRateLimiter} and {@code DuplicateCommentGuard}, and
 * the strikes {@code CommentStrikes} counts.
 */
@Configuration
@EnableConfigurationProperties({CommentLimitProperties.class, CommentStrikeProperties.class})
public class CommentLimitConfig {
}
