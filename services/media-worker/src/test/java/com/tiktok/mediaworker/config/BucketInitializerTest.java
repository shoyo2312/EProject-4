package com.tiktok.mediaworker.config;

import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import io.minio.SetBucketPolicyArgs;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The anonymous-read policy is applied from here on every startup, so this is the only place that
 * decides what the internet can read. A policy set by hand on the bucket would be overwritten by the
 * next restart.
 */
class BucketInitializerTest {

    private static final String BUCKET = "video-media";

    private static List<String> readableResources() {
        JsonNode policy = new ObjectMapper().readTree(BucketInitializer.readPolicyFor(BUCKET));
        List<String> resources = new ArrayList<>();
        policy.get("Statement").get(0).get("Resource").forEach(node -> resources.add(node.asString()));
        return resources;
    }

    @Test
    void opensTheChatStickerPackForAnonymousRead() {
        assertThat(readableResources()).contains("arn:aws:s3:::video-media/stickers/*");
    }

    @Test
    void keepsEveryPrefixItAlreadyServed() {
        assertThat(readableResources()).contains(
                "arn:aws:s3:::video-media/hls/*",
                "arn:aws:s3:::video-media/thumbnails/*",
                "arn:aws:s3:::video-media/previews/*",
                "arn:aws:s3:::video-media/avatars/*");
    }

    /** Opening one more prefix must never widen into the user's own uploads, exports or held-back media. */
    @Test
    void doesNotOpenTheBucketOrAnythingPrivate() {
        List<String> resources = readableResources();
        assertThat(resources).hasSize(5);
        assertThat(resources).allSatisfy(resource -> {
            assertThat(resource).startsWith("arn:aws:s3:::video-media/");
            assertThat(resource).endsWith("/*");
            assertThat(resource).doesNotContain("raw/", "exports/", "quarantine/");
        });
        assertThat(resources).doesNotContain("arn:aws:s3:::video-media", "arn:aws:s3:::video-media/*");
    }

    @Test
    void grantsReadOnly() {
        JsonNode statement = new ObjectMapper().readTree(BucketInitializer.readPolicyFor(BUCKET)).get("Statement").get(0);
        assertThat(statement.get("Effect").asString()).isEqualTo("Allow");
        assertThat(statement.get("Action")).hasSize(1);
        assertThat(statement.get("Action").get(0).asString()).isEqualTo("s3:GetObject");
    }

    @Test
    void appliesThePolicyOnEveryStartupNotOnlyWhenTheBucketIsCreated() throws Exception {
        MinioClient minio = mock(MinioClient.class);
        when(minio.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
        MinioProperties properties = new MinioProperties("http://minio:9000", "http://localhost:9000", "k", "s", BUCKET);

        new BucketInitializer(minio, properties).run(null);

        ArgumentCaptor<SetBucketPolicyArgs> applied = ArgumentCaptor.forClass(SetBucketPolicyArgs.class);
        verify(minio).setBucketPolicy(applied.capture());
        assertThat(applied.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(applied.getValue().config()).contains("video-media/stickers/*");
    }
}
