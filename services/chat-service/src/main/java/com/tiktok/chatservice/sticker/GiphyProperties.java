package com.tiktok.chatservice.sticker;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param apiKey  the GIPHY API key; blank turns the GIPHY picker off (it answers with no stickers)
 * @param baseUrl where the GIPHY API is reached
 */
@ConfigurationProperties(prefix = "chat.giphy")
public record GiphyProperties(String apiKey, String baseUrl) {

    public GiphyProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://api.giphy.com";
        }
    }

    public boolean enabled() {
        return apiKey != null && !apiKey.isBlank();
    }
}
