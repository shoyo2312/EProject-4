#!/bin/bash
# Everything about the media bucket that is not an object: the bucket itself and its lifecycle.
#
# Runs on every `up`, so every step is safe to repeat: mb ignores a bucket that exists, ilm import
# replaces what is there.
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

echo "minio-init: bucket $MINIO_BUCKET ready"
