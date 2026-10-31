package com.tiktok.storyservice.service;

import com.tiktok.storyservice.config.MinioProperties;
import com.tiktok.storyservice.config.StoryUploadLimits;
import com.tiktok.storyservice.entity.StoryMediaType;
import com.tiktok.storyservice.exception.ForeignUploadException;
import com.tiktok.storyservice.exception.UnsupportedUploadTypeException;
import io.minio.MinioClient;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class StoryMediaTest {

    private final StoryMedia media = new StoryMedia(mock(MinioClient.class),
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
}
