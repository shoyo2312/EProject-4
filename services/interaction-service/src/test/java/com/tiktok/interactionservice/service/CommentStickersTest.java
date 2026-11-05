package com.tiktok.interactionservice.service;

import com.tiktok.interactionservice.config.CommentStickerProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CommentStickersTest {

    private static final String MANIFEST = """
            {"packId":"p","stickers":[
              {"stickerId":"a","objectKey":"stickers/p/a.png","width":256,"height":128},
              {"stickerId":"old","objectKey":"stickers/p/old.png","width":64,"height":64,"retired":true}
            ]}""";

    @Test
    void aRetiredSticker_cannotBeSent_butStillDraws() {
        CommentStickers stickers = CommentStickers.fromJson(MANIFEST, "http://media/", "bucket");

        assertThat(stickers.sendable("a")).isTrue();
        assertThat(stickers.sendable("old")).isFalse();
        assertThat(stickers.sendable("nope")).isFalse();
        assertThat(stickers.sendable(null)).isFalse();

        assertThat(stickers.view("old").url()).isEqualTo("http://media/bucket/stickers/p/old.png");
        assertThat(stickers.view("a").width()).isEqualTo(256);
        assertThat(stickers.view("nope")).isNull();
        assertThat(stickers.view(null)).isNull();
    }

    /** chat-service's fixture pack is on the test classpath: copied in by this module's build. */
    @Test
    void theFixturePack_isOnTheClasspath() {
        CommentStickers stickers = new CommentStickers(
                new CommentStickerProperties("http://localhost:9000", "video-media"));

        assertThat(stickers.sendable("grinning-face")).isTrue();
    }
}
