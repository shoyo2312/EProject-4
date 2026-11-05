package com.tiktok.chatservice.sticker;

import com.tiktok.chatservice.dto.response.StickerPackResponse;
import com.tiktok.chatservice.dto.response.StickerView;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The sticker pack, read from the same manifest the importer uploads from
 * ({@code stickers/fluent-3d-v1.json}, when one is on the classpath), so the backend and the images in MinIO cannot disagree about
 * which ids exist. Messages store only an id; this is where an id becomes something a client can draw.
 *
 * <p>Two questions, deliberately separate: {@link #sendable} (may a client send this now?) and
 * {@link #find} (what is this, for drawing a message already sent?). A sticker marked
 * {@code "retired": true} in the manifest fails the first and still answers the second, so stopping
 * a sticker from being offered never breaks the history that contains it.
 */
public final class StickerCatalog {

    public static final String MANIFEST = "stickers/fluent-3d-v1.json";

    /** A GIPHY sticker is named {@code giphy:<id>}; its picture is always served from this one host. */
    public static final String GIPHY_PREFIX = "giphy:";
    private static final java.util.regex.Pattern GIPHY_ID = java.util.regex.Pattern.compile("giphy:[A-Za-z0-9]{5,40}");
    private static final String GIPHY_MEDIA = "https://media.giphy.com/media/";
    private static final int GIPHY_SIZE = 200;

    /** The view of a GIPHY sticker. Only the id comes from a client, never a URL. */
    public static StickerView giphy(String giphyId, String name) {
        return new StickerView(GIPHY_PREFIX + giphyId, name, GIPHY_MEDIA + giphyId + "/giphy.webp", GIPHY_SIZE, GIPHY_SIZE);
    }

    private record Source(String repository, String commit, String license, String licenseFile, String copyright) {
    }

    private record Entry(String stickerId, String name, String sourcePath, String objectKey,
                         Integer width, Integer height, Integer bytes, String sha256, Boolean retired) {
    }

    private record Manifest(String packId, String packName, Integer version, Source source,
                            String objectPrefix, List<Entry> stickers) {
    }

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private final String packId;
    private final String packName;
    private final Map<String, StickerView> all = new LinkedHashMap<>();
    private final Map<String, Boolean> retired = new LinkedHashMap<>();

    /** No manifest on the classpath: nothing is offered and no id resolves. */
    private StickerCatalog() {
        this.packId = "none";
        this.packName = "Stickers";
    }

    private StickerCatalog(Manifest manifest, String publicBaseUrl, String bucket) {
        this.packId = manifest.packId();
        this.packName = manifest.packName();
        String base = publicBaseUrl.replaceAll("/+$", "") + "/" + bucket + "/";
        for (Entry entry : manifest.stickers()) {
            if (entry.sha256() == null || entry.width() == null || entry.height() == null) {
                throw new IllegalStateException("Sticker " + entry.stickerId()
                        + " has no pinned size/hash: run scripts/stickers/import-chat-stickers.mjs --fill");
            }
            if (all.put(entry.stickerId(), new StickerView(entry.stickerId(), entry.name(),
                    base + entry.objectKey(), entry.width(), entry.height())) != null) {
                throw new IllegalStateException("Duplicate sticker id " + entry.stickerId());
            }
            retired.put(entry.stickerId(), Boolean.TRUE.equals(entry.retired()));
        }
    }

    public static StickerCatalog load(StickerProperties properties) {
        return load(properties.publicBaseUrl(), properties.bucket());
    }

    public static StickerCatalog load(String publicBaseUrl, String bucket) {
        try (InputStream in = StickerCatalog.class.getClassLoader().getResourceAsStream(MANIFEST)) {
            if (in == null) {
                return new StickerCatalog();
            }
            return new StickerCatalog(MAPPER.readValue(in, Manifest.class), publicBaseUrl, bucket);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Builds a catalog from manifest text — for a test that needs, say, a retired sticker. */
    public static StickerCatalog fromJson(String manifestJson, String publicBaseUrl, String bucket) {
        return new StickerCatalog(MAPPER.readValue(manifestJson, Manifest.class), publicBaseUrl, bucket);
    }

    /** Any sticker that ever existed, retired or not — what drawing a stored message needs. */
    public Optional<StickerView> find(String stickerId) {
        if (isGiphy(stickerId)) {
            return Optional.of(giphy(stickerId.substring(GIPHY_PREFIX.length()), "GIPHY sticker"));
        }
        return stickerId == null ? Optional.empty() : Optional.ofNullable(all.get(stickerId));
    }

    /** Whether a client may send this sticker now. */
    public boolean sendable(String stickerId) {
        return isGiphy(stickerId) || stickerId != null && all.containsKey(stickerId) && !retired.get(stickerId);
    }

    private static boolean isGiphy(String stickerId) {
        return stickerId != null && GIPHY_ID.matcher(stickerId).matches();
    }

    /** The pack as offered for sending: retired stickers left out. */
    public StickerPackResponse pack() {
        List<StickerView> offered = all.values().stream().filter(s -> !retired.get(s.stickerId())).toList();
        return new StickerPackResponse(packId, packName, offered);
    }
}
