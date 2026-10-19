package com.tiktok.common.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * video-service's ownership check and media-worker's fetch and delete all go through this, so the
 * answers here are the answers both sides act on.
 */
class ObjectKeysTest {

    private static final String BUCKET = "video-media";

    @ParameterizedTest
    @CsvSource({
            "s3://video-media/raw/7/x.mp4, raw/7/x.mp4",
            "https://cdn.example.com/video-media/raw/7/x.mp4, raw/7/x.mp4",
            // A CDN may put its own prefix in front; the key is measured from the bucket.
            "https://cdn.example.com/media/video-media/raw/7/x.mp4, raw/7/x.mp4",
            "s3://video-media/raw/7/../8/x.mp4, raw/8/x.mp4",
            "https://cdn.example.com/video-media/raw/7/../8/x.mp4, raw/8/x.mp4"
    })
    void readsTheKeyAfterTheBucket(String url, String key) {
        assertThat(ObjectKeys.objectKey(url, BUCKET)).contains(key);
    }

    /**
     * The first bucket-named segment wins, wherever it sits. Whatever reads ownership off this URL
     * has to call this method too; anything reading the segment after {@code raw} instead sees
     * user 1 here.
     */
    @Test
    void theKeyStartsAtTheFirstBucketSegment() {
        assertThat(ObjectKeys.objectKey("https://cdn.example.com/raw/1/video-media/raw/2/x.mp4", BUCKET))
                .contains("raw/2/x.mp4");
    }

    /** normalize() sees one encoded segment; the dot segments only appear once getPath() decodes. */
    @Test
    void encodedSlashesDecodeAfterNormalization() {
        assertThat(ObjectKeys.objectKey("https://cdn.example.com/video-media/raw/1/x%2F..%2F2%2Fy.mp4", BUCKET))
                .contains("raw/1/x/../2/y.mp4");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "   ",
            "https://cdn.example.com/other-bucket/raw/7/x.mp4",
            "s3://other-bucket/raw/7/x.mp4",
            "s3://video-media",
            "s3://video-media/",
            // The bucket as the last segment names no object.
            "https://cdn.example.com/video-media",
            "not a uri"
    })
    void emptyWhenTheUrlNamesNoObjectInTheBucket(String url) {
        assertThat(ObjectKeys.objectKey(url, BUCKET)).isEmpty();
    }

    @Test
    void emptyForNull() {
        assertThat(ObjectKeys.objectKey(null, BUCKET)).isEmpty();
    }
}
