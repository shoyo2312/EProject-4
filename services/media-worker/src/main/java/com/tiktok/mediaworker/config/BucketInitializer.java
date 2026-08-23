package com.tiktok.mediaworker.config;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.SetBucketPolicyArgs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Creates the media bucket on startup so a fresh MinIO container (no manual setup) works
 * out of the box in local/dev environments, and opens the two derived prefixes for reading.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BucketInitializer implements ApplicationRunner {

    /**
     * Anonymous read on the transcode's output, and on nothing else.
     *
     * <p>The URLs this service writes onto a Video — {@code .../hls/{id}/master.m3u8} and
     * {@code .../thumbnails/{id}.jpg} — carry no signature, because a player follows them for as
     * long as someone watches and a signature that expires mid-playback is worse than none. So
     * the objects behind them have to be readable without credentials, or every player gets a
     * 403 on a video the API happily says is PUBLISHED.
     *
     * <p>{@code avatars/} joins them for the same reason: a profile picture is loaded by every
     * viewer of every comment, and it is a copy of something the provider was already serving to
     * anyone with the URL.
     *
     * <p>{@code raw/} is deliberately excluded and must stay excluded. Those are the users' own
     * uploads, the key names the uploader, and they are reached through a presigned PUT that
     * expires in minutes — publishing them would hand out every original ever uploaded.
     *
     * <p>This grants read to anyone who can reach MinIO, so a real deployment puts the bucket
     * behind a CDN and does not expose the origin. Visibility is not enforced here: a video
     * turned PRIVATE after transcoding keeps readable media, and only its metadata stops being
     * served. Signed CDN URLs are the fix when that matters.
     */
    private static final String READABLE_OUTPUT_POLICY = """
            {
              "Version": "2012-10-17",
              "Statement": [{
                "Effect": "Allow",
                "Principal": {"AWS": ["*"]},
                "Action": ["s3:GetObject"],
                "Resource": [
                  "arn:aws:s3:::%1$s/hls/*",
                  "arn:aws:s3:::%1$s/thumbnails/*",
                  "arn:aws:s3:::%1$s/avatars/*"
                ]
              }]
            }""";

    private final MinioClient minioClient;
    private final MinioProperties properties;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(properties.bucket()).build());
        if (!exists) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(properties.bucket()).build());
        }

        // Re-applied on every startup rather than only on creation: a bucket that predates this
        // policy — every one created before it existed — would otherwise stay unreadable forever,
        // serving 403 for media the API reports as published.
        try {
            minioClient.setBucketPolicy(SetBucketPolicyArgs.builder()
                    .bucket(properties.bucket())
                    .config(READABLE_OUTPUT_POLICY.formatted(properties.bucket()))
                    .build());
        } catch (Exception e) {
            // Not fatal: transcoding still works and still writes the files. What breaks is
            // playback, and it breaks visibly, so the worker starting is better than it not.
            log.error("Could not set the read policy on bucket {} — transcoded media will 403 in "
                    + "players until it is applied", properties.bucket(), e);
        }
    }
}
