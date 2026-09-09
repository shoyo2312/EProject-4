package com.tiktok.videoservice.mapper;

import com.tiktok.event.video.ModerationVerdict;
import com.tiktok.videoservice.dto.response.VideoResponse;
import com.tiktok.videoservice.entity.Video;
import com.tiktok.videoservice.entity.VideoModeration;
import com.tiktok.videoservice.entity.VideoStatus;
import com.tiktok.videoservice.entity.VideoVisibility;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class VideoMapperTest {

    private final VideoMapper mapper = new VideoMapperImpl();

    private Video rejectedVideo() {
        return Video.builder()
                .id("1")
                .userId(7L)
                .title("t")
                .visibility(VideoVisibility.PUBLIC)
                .status(VideoStatus.REJECTED)
                .moderation(VideoModeration.builder()
                        .verdict(ModerationVerdict.REJECTED)
                        .label("nsfw")
                        .maxScore(0.0105)
                        .suspiciousFrames(9)
                        .totalFrames(9)
                        .model("Falconsai/nsfw_image_detection")
                        .modelVersion("nsfw-v1")
                        .checkedAt(Instant.now())
                        .build())
                .build();
    }

    @Test
    void toAdminResponse_carriesTheScore() {
        VideoResponse response = mapper.toAdminResponse(rejectedVideo());

        // A machine-removed video sets no takedownReason, so this block is the whole explanation.
        assertThat(response.takedownReason()).isNull();
        assertThat(response.moderation()).isNotNull();
        assertThat(response.moderation().verdict()).isEqualTo(ModerationVerdict.REJECTED);
        assertThat(response.moderation().label()).isEqualTo("nsfw");
        assertThat(response.moderation().maxScore()).isEqualTo(0.0105);
        assertThat(response.moderation().suspiciousFrames()).isEqualTo(9);
        assertThat(response.moderation().totalFrames()).isEqualTo(9);
    }

    @Test
    void toResponse_withholdsTheScore() {
        // The score is the distance to the threshold; an uploader who can watch it move can
        // binary-search their way to just under it.
        assertThat(mapper.toResponse(rejectedVideo()).moderation()).isNull();
    }
}
