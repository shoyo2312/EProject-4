#!/bin/bash
# Everything about the media bucket that is not an object: the bucket itself, its lifecycle, and —
# when their secrets are set, as docker-compose.prod.yml requires — one MinIO account per service.
#
# Runs on every `up`, so every step is safe to repeat: mb ignores a bucket that exists, ilm import
# and policy create replace what is there, user add resets the secret, attach is a no-op when the
# policy is already attached.
set -euo pipefail

mc alias set tt http://minio:9000 "$MINIO_ACCESS_KEY" "$MINIO_SECRET_KEY"

# Without the bucket, video-service happily signs an upload for a bucket that does not exist —
# presigning is a local signature and never contacts the server — and the client only finds out
# when its upload returns NoSuchBucket. media-worker creates it too, but video-service can run alone.
mc mb --ignore-existing "tt/$MINIO_BUCKET"

# raw/ and exports/ expire after 7 days: /upload-url persists nothing, so an upload no /videos call
# followed is an object nothing references, and an export link is good for far less than a week.
# stories/ after 3 days, a day past the longest a story lives (48h pending + 24h): story-service's
# TTL removes the documents and nothing else deletes the objects behind them.
#
# Set here and only here. A lifecycle write replaces the bucket's whole config, so a second writer
# (media-worker once set stories/ on startup) wipes these rules, and these wipe it, whichever runs
# last.
mc ilm import "tt/$MINIO_BUCKET" < /minio/lifecycle.json

# The root account creates users, reads and deletes everything, and rewrites every policy. A
# service that only signs uploads to raw/ has no business holding it — least of all media-worker,
# where ffmpeg parses whatever users upload. Each account reaches its own prefixes and nothing else:
# see policies/. Read access to a prefix is what makes a missing object a 404 (NoSuchKey) rather
# than a 403, and video-service and story-service tell "not uploaded" from "storage down" by it.
grant() {
    local name=$1 secret=$2
    if [ -z "$secret" ]; then
        echo "minio-init: no secret for $name, no account made (locally every service uses root)"
        return
    fi
    local policy
    policy=$(cat "/minio/policies/$name.json")
    printf '%s' "${policy//BUCKET/$MINIO_BUCKET}" > "/tmp/$name.json"
    mc admin policy create tt "$name" "/tmp/$name.json"
    mc admin user add tt "$name" "$secret"
    mc admin policy attach tt "$name" --user "$name"
}

grant video-service "${MINIO_VIDEO_SECRET_KEY:-}"
grant story-service "${MINIO_STORY_SECRET_KEY:-}"
grant user-service "${MINIO_USER_SECRET_KEY:-}"
grant media-worker "${MINIO_WORKER_SECRET_KEY:-}"
# Read-only, for the backup container's off-site mirror of the media.
grant backup "${MINIO_BACKUP_SECRET_KEY:-}"

echo "minio-init: bucket $MINIO_BUCKET ready"
