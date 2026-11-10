package com.tiktok.storyservice.service;

import com.tiktok.storyservice.config.MinioProperties;
import com.tiktok.storyservice.config.StoryUploadLimits;
import com.tiktok.storyservice.entity.StoryMediaType;
import com.tiktok.storyservice.exception.ForeignUploadException;
import com.tiktok.storyservice.exception.UnsupportedUploadTypeException;
import io.minio.CopyObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PostPolicy;
import io.minio.StatObjectArgs;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StoryMediaTest {

    private final StoryMedia media = new StoryMedia(mock(MinioClient.class), mock(MinioClient.class),
            new MinioProperties("http://minio", "http://minio", "a", "s", "bucket", "us-east-1", Duration.ofMinutes(15)),
            new StoryUploadLimits(10_485_760L, 52_428_800L));

    @Test
    void foreignKeyIsRejected() {
        assertThatThrownBy(() -> media.keyOf(7L, "s3://bucket/stories/8/1.jpg"))
                .isInstanceOf(ForeignUploadException.class);
        assertThatThrownBy(() -> media.keyOf(7L, "s3://bucket/raw/7/1.mp4"))
                .isInstanceOf(ForeignUploadException.class);
        assertThatThrownBy(() -> media.keyOf(7L, "s3://bucket/stories/7/x/../../8/1.jpg"))
                .isInstanceOf(ForeignUploadException.class);

        assertThat(media.keyOf(7L, "s3://bucket/stories/7/1.jpg")).isEqualTo("stories/7/1.jpg");
    }

    /**
     * An upload form stays good for its whole lifetime, so the upload key can be written again after
     * the story was approved. The story's copy has one segment more than any key presignUpload signs:
     * no upload form can target it, and no upload URL can name it.
     */
    @Test
    void storyCopyHasAKeyNoUploadCanName() {
        String storyKey = media.copyForStory("stories/7/1.jpg", "55");

        assertThat(storyKey).isEqualTo("stories/7/55/media.jpg");
        assertThatThrownBy(() -> media.keyOf(7L, "s3://bucket/" + storyKey))
                .isInstanceOf(ForeignUploadException.class);
    }

    @Test
    void typeFromExtension() {
        assertThat(media.typeOf("stories/7/1.jpg")).isEqualTo(StoryMediaType.IMAGE);
        assertThat(media.typeOf("stories/7/1.png")).isEqualTo(StoryMediaType.IMAGE);
        assertThat(media.typeOf("stories/7/1.webp")).isEqualTo(StoryMediaType.IMAGE);
        assertThat(media.typeOf("stories/7/1.mp4")).isEqualTo(StoryMediaType.VIDEO);
        assertThat(media.typeOf("stories/7/1.webm")).isEqualTo(StoryMediaType.VIDEO);
        assertThatThrownBy(() -> media.typeOf("stories/7/1.mov"))
                .isInstanceOf(UnsupportedUploadTypeException.class);
    }

    @Test
    void uploadOfAnUnacceptedTypeIsRefusedBeforeSigning() {
        assertThatThrownBy(() -> media.presignUpload(7L, "video/quicktime"))
                .isInstanceOf(UnsupportedUploadTypeException.class);
    }

    /**
     * In production the public endpoint is reached through Cloudflare, and a container with no IPv6
     * route cannot always get there: the create's own calls go in-cluster, and only what an app or a
     * browser will use is signed for the public host.
     */
    @Test
    void ownCallsGoInClusterAndLinksAreSignedForThePublicHost() throws Exception {
        MinioClient internal = mock(MinioClient.class);
        MinioClient published = mock(MinioClient.class);
        when(published.getPresignedPostFormData(any(PostPolicy.class))).thenReturn(java.util.Map.of());
        when(published.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class))).thenReturn("https://media/x");
        StoryMedia split = new StoryMedia(internal, published,
                new MinioProperties("https://media", "https://media", "a", "s", "bucket", "us-east-1",
                        Duration.ofMinutes(15)),
                new StoryUploadLimits(10_485_760L, 52_428_800L));

        split.exists("stories/7/1.mp4");
        split.copyForStory("stories/7/1.mp4", "57");
        verify(internal).statObject(any(StatObjectArgs.class));
        verify(internal).copyObject(any(CopyObjectArgs.class));

        split.presignUpload(7L, "video/mp4");
        assertThat(split.signedGet("stories/7/57/media.mp4")).isEqualTo("https://media/x");
        verify(published).getPresignedPostFormData(any(PostPolicy.class));
        verify(published).getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class));
        verify(published, org.mockito.Mockito.never()).statObject(any(StatObjectArgs.class));
        verify(published, org.mockito.Mockito.never()).copyObject(any(CopyObjectArgs.class));
    }
}
