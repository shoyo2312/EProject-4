package com.tiktok.interactionservice.service;

import com.tiktok.interactionservice.config.CommentStickerProperties;
import com.tiktok.interactionservice.dto.response.CommentSticker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The stickers a comment may carry: chat-service's pack. The manifest is chat-service's own file,
 * copied onto this service's classpath at build time (see this module's pom), so the two services
 * agree on which ids exist without one calling the other on every comment.
 *
 * <p>Two questions, as in chat-service: {@link #sendable} (may a new comment use it?) and
 * {@link #view} (how to draw one already stored?). A sticker the manifest marks {@code retired} fails
 * the first and still answers the second, so retiring a sticker never blanks old comments.
 *
 * <p>Also MapStruct's way from a stored {@code sticker_id} to the response's {@code sticker} — see
 * {@link com.tiktok.interactionservice.mapper.CommentMapper}.
 */
@Component
public class CommentStickers {

    static final String MANIFEST = "stickers/fluent-3d-v1.json";

    private record Entry(String stickerId, String objectKey, Integer width, Integer height, Boolean retired) {
    }

    private record Manifest(List<Entry> stickers) {
    }

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .disable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private final Map<String, CommentSticker> all = new HashMap<>();
    private final Set<String> retired = new HashSet<>();

    @Autowired
    public CommentStickers(CommentStickerProperties properties) {
        load(read(), properties.publicBaseUrl(), properties.bucket());
    }

    private CommentStickers() {
    }

    /** From manifest text — for a test that needs, say, a retired sticker. */
    static CommentStickers fromJson(String manifestJson, String publicBaseUrl, String bucket) {
        CommentStickers stickers = new CommentStickers();
        stickers.load(MAPPER.readValue(manifestJson, Manifest.class), publicBaseUrl, bucket);
        return stickers;
    }

    private void load(Manifest manifest, String publicBaseUrl, String bucket) {
        String base = publicBaseUrl.replaceAll("/+$", "") + "/" + bucket + "/";
        for (Entry entry : manifest.stickers()) {
            all.put(entry.stickerId(), new CommentSticker(entry.stickerId(), base + entry.objectKey(),
                    entry.width() == null ? 0 : entry.width(), entry.height() == null ? 0 : entry.height()));
            if (Boolean.TRUE.equals(entry.retired())) {
                retired.add(entry.stickerId());
            }
        }
    }

    private static Manifest read() {
        try (InputStream in = CommentStickers.class.getClassLoader().getResourceAsStream(MANIFEST)) {
            if (in == null) {
                throw new IllegalStateException("Sticker manifest not found on the classpath: " + MANIFEST
                        + " — it is copied from chat-service by the interaction-service build");
            }
            return MAPPER.readValue(in, Manifest.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Whether a new comment may use this sticker now. */
    public boolean sendable(String stickerId) {
        return stickerId != null && all.containsKey(stickerId) && !retired.contains(stickerId);
    }

    /**
     * How to draw a stored sticker, or null when the comment has none — or names one the pack no
     * longer has, which then simply shows nothing rather than failing the whole page.
     */
    public CommentSticker view(String stickerId) {
        return stickerId == null ? null : all.get(stickerId);
    }
}
