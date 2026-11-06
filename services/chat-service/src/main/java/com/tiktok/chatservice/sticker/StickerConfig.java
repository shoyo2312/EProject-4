package com.tiktok.chatservice.sticker;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({StickerProperties.class, GiphyProperties.class})
public class StickerConfig {

    /** Built once at startup: a manifest that is missing or incomplete stops the service rather than failing on a send. */
    @Bean
    public StickerCatalog stickerCatalog(StickerProperties properties) {
        return StickerCatalog.load(properties);
    }

    /** Built once at startup, like the catalog: a malformed table stops the service rather than failing on a keystroke. */
    @Bean
    public SuggestionTopics suggestionTopics() {
        return SuggestionTopics.load();
    }
}
