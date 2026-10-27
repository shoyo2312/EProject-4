package com.tiktok.chatservice.sticker;

import com.tiktok.chatservice.dto.response.StickerPackResponse;
import com.tiktok.chatservice.dto.response.StickerView;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The catalog is read from the same manifest the importer uploads from. */
class StickerCatalogTest {

    private static final String BASE = "http://localhost:9000";
    private static final String BUCKET = "video-media";

    private final StickerCatalog catalog = StickerCatalog.load(BASE, BUCKET);

    private JsonNode manifest() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(StickerCatalog.MANIFEST)) {
            return JsonMapper.builder().build().readTree(in);
        }
    }

    @Test
    void offersTwentyFourStickersWithStableUniqueIds() {
        StickerPackResponse pack = catalog.pack();
        assertThat(pack.packId()).isEqualTo("fluent-3d");
        assertThat(pack.stickers()).hasSize(24);
        assertThat(new HashSet<>(pack.stickers().stream().map(StickerView::stickerId).toList())).hasSize(24);
    }

    @Test
    void buildsUnsignedPublicUrlsFromTheMediaBaseAndTheObjectKey() {
        StickerView sticker = catalog.find("grinning-face").orElseThrow();
        assertThat(sticker.url()).isEqualTo("http://localhost:9000/video-media/stickers/fluent-3d/v1/grinning-face.png");
        assertThat(sticker.url()).doesNotContain("?", "X-Amz", "minio:9000");
        assertThat(sticker.width()).isEqualTo(256);
        assertThat(sticker.height()).isEqualTo(256);
    }

    @Test
    void aTrailingSlashOnTheBaseDoesNotDoubleUp() {
        assertThat(StickerCatalog.load("https://cdn.example.com/", "media").find("fire").orElseThrow().url())
                .isEqualTo("https://cdn.example.com/media/stickers/fluent-3d/v1/fire.png");
    }

    @Test
    void everyManifestEntryIsPinnedAndKeyedUnderTheVersionedPrefix() throws Exception {
        JsonNode manifest = manifest();
        assertThat(manifest.get("source").get("commit").asString()).matches("[0-9a-f]{40}");
        assertThat(manifest.get("source").get("license").asString()).isEqualTo("MIT");
        Set<String> keys = new HashSet<>();
        for (JsonNode sticker : manifest.get("stickers")) {
            String id = sticker.get("stickerId").asString();
            assertThat(sticker.get("objectKey").asString()).isEqualTo("stickers/fluent-3d/v1/" + id + ".png");
            assertThat(sticker.get("sourcePath").asString()).startsWith("assets/").endsWith("_3d.png");
            assertThat(sticker.get("sha256").asString()).hasSize(64);
            assertThat(sticker.get("bytes").asInt()).isPositive();
            keys.add(sticker.get("objectKey").asString());
        }
        assertThat(keys).hasSize(24);
    }

    @Test
    void anUnknownMissingOrNullIdIsNotSendableAndNotFound() {
        assertThat(catalog.sendable("nope")).isFalse();
        assertThat(catalog.sendable(null)).isFalse();
        assertThat(catalog.sendable("")).isFalse();
        assertThat(catalog.find("nope")).isEmpty();
        assertThat(catalog.find(null)).isEmpty();
    }

    /** Stopping a sticker from being offered is not deleting it: history that contains it keeps drawing. */
    @Test
    void aRetiredStickerIsNotOfferedOrSendableButStillResolvesForHistory() {
        String json = """
                {"packId":"p","packName":"P","version":1,"objectPrefix":"stickers/p/v1/",
                 "source":{"repository":"r","commit":"c","license":"MIT","licenseFile":"l","copyright":"c"},
                 "stickers":[
                   {"stickerId":"live","name":"Live","sourcePath":"a","objectKey":"stickers/p/v1/live.png","width":256,"height":256,"bytes":1,"sha256":"x"},
                   {"stickerId":"old","name":"Old","sourcePath":"b","objectKey":"stickers/p/v1/old.png","width":256,"height":256,"bytes":1,"sha256":"y","retired":true}
                 ]}""";
        StickerCatalog custom = StickerCatalog.fromJson(json, BASE, BUCKET);

        assertThat(custom.pack().stickers()).extracting(StickerView::stickerId).containsExactly("live");
        assertThat(custom.sendable("live")).isTrue();
        assertThat(custom.sendable("old")).isFalse();
        assertThat(custom.find("old")).isPresent();
    }

    @Test
    void refusesAnEntryThatIsNotPinned() {
        String json = """
                {"packId":"p","packName":"P","version":1,"objectPrefix":"x/",
                 "source":{"repository":"r","commit":"c","license":"MIT","licenseFile":"l","copyright":"c"},
                 "stickers":[{"stickerId":"a","name":"A","sourcePath":"a","objectKey":"x/a.png"}]}""";
        assertThatThrownBy(() -> StickerCatalog.fromJson(json, BASE, BUCKET))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("--fill");
    }
}
