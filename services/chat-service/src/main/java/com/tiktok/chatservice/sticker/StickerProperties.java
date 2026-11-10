package com.tiktok.chatservice.sticker;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param publicBaseUrl what a browser uses to reach the media store (not the in-cluster address this
 *                      service could reach it by): the sticker URLs are built from it
 * @param bucket        the media bucket the importer uploaded the pack to
 */
@ConfigurationProperties(prefix = "chat.stickers")
public record StickerProperties(String publicBaseUrl, String bucket) {
}
