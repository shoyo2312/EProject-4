package com.tiktok.mediaworker.service;

import com.tiktok.mediaworker.config.MinioProperties;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.messages.Item;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MediaCleanupServiceImplTest {

    private static final MinioProperties PROPERTIES =
            new MinioProperties("http://localhost:9000", "key", "secret", "video-media");

    @Mock
    private MinioClient minioClient;

    private MediaCleanupServiceImpl service() {
        return new MediaCleanupServiceImpl(minioClient, PROPERTIES);
    }

    private List<String> removedKeys() throws Exception {
        ArgumentCaptor<RemoveObjectArgs> captor = ArgumentCaptor.forClass(RemoveObjectArgs.class);
        verify(minioClient, org.mockito.Mockito.atLeastOnce()).removeObject(captor.capture());
        return captor.getAllValues().stream().map(RemoveObjectArgs::object).toList();
    }

    /**
     * A real transcode writes a playlist plus a segment per few seconds, so only the playlist key
     * is deterministic. Deleting the keys this service happens to know about would leave every
     * segment behind — which is most of the bytes.
     */
    @Test
    void deleteMediaFor_removesEveryObjectUnderTheHlsPrefix() throws Exception {
        stubListing("hls/vid1/master.m3u8", "hls/vid1/0.ts", "hls/vid1/1.ts");

        service().deleteMediaFor("vid1", "s3://video-media/raw/7/vid1.mp4");

        assertThat(removedKeys()).contains("hls/vid1/master.m3u8", "hls/vid1/0.ts", "hls/vid1/1.ts");
    }

    @Test
    void deleteMediaFor_removesTheThumbnailAndTheSourceUpload() throws Exception {
        stubListing();

        service().deleteMediaFor("vid1", "s3://video-media/raw/7/vid1.mp4");

        assertThat(removedKeys()).contains("thumbnails/vid1.jpg", "raw/7/vid1.mp4");
    }

    /**
     * The same upload reaches this service as an s3:// URI and as an https CDN URL, which put a
     * different number of segments in front of the key.
     */
    @Test
    void deleteMediaFor_readsTheKeyOutOfAnHttpsUrlToo() throws Exception {
        stubListing();

        service().deleteMediaFor("vid1", "https://cdn.example.test/video-media/raw/7/vid1.mp4");

        assertThat(removedKeys()).contains("raw/7/vid1.mp4");
    }

    /**
     * A URL naming some other bucket gives no key that can be trusted, and a guess here deletes
     * whatever object the guess happens to hit.
     */
    @Test
    void deleteMediaFor_urlOutsideTheBucket_removesNothingItCannotAddress() throws Exception {
        stubListing();

        service().deleteMediaFor("vid1", "https://elsewhere.example.test/other-bucket/raw/7/vid1.mp4");

        assertThat(removedKeys()).containsExactly("thumbnails/vid1.jpg");
    }

    /** One object that will not go must not take the rest of the cleanup with it. */
    @Test
    void deleteMediaFor_oneFailedRemoval_doesNotStopTheOthers() throws Exception {
        stubListing("hls/vid1/0.ts");
        org.mockito.Mockito.doThrow(new RuntimeException("gone"))
                .doNothing()
                .when(minioClient).removeObject(any(RemoveObjectArgs.class));

        service().deleteMediaFor("vid1", "s3://video-media/raw/7/vid1.mp4");

        assertThat(removedKeys()).hasSize(3);
    }

    /**
     * Built before the stubbing starts, not inside it: mocking the items from within the
     * {@code when(...)} argument nests one stubbing inside another and Mockito rejects it.
     */
    private void stubListing(String... keys) {
        List<Result<Item>> results = java.util.Arrays.stream(keys)
                .map(key -> {
                    Item item = org.mockito.Mockito.mock(Item.class);
                    when(item.objectName()).thenReturn(key);
                    return new Result<>(item);
                })
                .toList();
        when(minioClient.listObjects(any(ListObjectsArgs.class))).thenReturn(results);
    }
}
