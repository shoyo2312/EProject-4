#!/bin/bash
# Export the seeded local stack into one directory, to be shipped to the production server and
# loaded there by import-prod.sh. Runs on the developer machine against the `tt-*` containers of
# docker-compose.yml; the Java services may keep running (reads only).
#
#   bash scripts/seed/export-prod.sh [OUT_DIR]          # default ~/seed-export
#   scp -r ~/seed-export homebox:~/
#
# What goes where (versions are the same on both sides, so logical dumps everywhere a tool exists):
#   Postgres   pg_dump -Fc          auth_db user_db admin_db
#   MongoDB    mongodump archives   video_db story_db chat_db notification_db
#   Cassandra  schema + COPY TO     interaction_ks
#   ClickHouse Native               analytics_db, every table
#   Elastic    bulk ndjson          videos, owner_bans   (processed_events is Kafka idempotency, not needed)
#   Redis      the data dir         appendonlydir (AOF is on) — the recommendation inboxes live here
#   MinIO      the bucket dir only  video-media/ (never .minio.sys: that holds prod's accounts and policies)
set -euo pipefail

OUT=${1:-$HOME/seed-export}
mkdir -p "$OUT"/{postgres,mongo,cassandra,clickhouse,elasticsearch}
log() { echo "export: $*"; }

# --- Postgres ---------------------------------------------------------------------------------
for spec in tt-postgres-auth:auth_db tt-postgres-user:user_db tt-postgres-admin:admin_db; do
    IFS=: read -r container db <<< "$spec"
    docker exec "$container" pg_dump -U postgres -d "$db" -Fc > "$OUT/postgres/$db.dump"
    log "postgres $db $(du -h "$OUT/postgres/$db.dump" | cut -f1)"
done

# --- MongoDB (one archive per database, so the restore can --drop each on its own) -------------
for db in video_db story_db chat_db notification_db; do
    docker exec tt-mongodb mongodump --username root --password password --authenticationDatabase admin \
        --db "$db" --gzip --archive --quiet > "$OUT/mongo/$db.archive.gz"
    log "mongo $db $(du -h "$OUT/mongo/$db.archive.gz" | cut -f1)"
done

# --- Cassandra (the same shape backup.sh writes, restored by the steps in docs/operations.md) ---
docker exec tt-cassandra cqlsh -e "DESCRIBE KEYSPACE interaction_ks" > "$OUT/cassandra/schema.cql"
tables=$(docker exec tt-cassandra cqlsh -e \
    "SELECT table_name FROM system_schema.tables WHERE keyspace_name = 'interaction_ks'" \
    | grep -vE 'table_name|^-|rows\)|^\s*$' | tr -d ' ')
for t in $tables; do
    docker exec tt-cassandra cqlsh -e "COPY interaction_ks.$t TO STDOUT WITH HEADER = true" \
        | grep -vE '^(Using|Processed|Starting|Exported)' > "$OUT/cassandra/$t.csv"
done
log "cassandra $(echo "$tables" | wc -w | tr -d ' ') tables"

# --- ClickHouse -------------------------------------------------------------------------------
ch() { docker exec tt-clickhouse clickhouse-client --password clickhouse -d analytics_db --query "$1"; }
for t in $(ch "SELECT name FROM system.tables WHERE database = 'analytics_db' FORMAT TSV"); do
    ch "SELECT * FROM $t FORMAT Native" > "$OUT/clickhouse/$t.native"
done
log "clickhouse $(ls "$OUT/clickhouse" | wc -l | tr -d ' ') tables"

# --- Elasticsearch: every hit as a bulk `index` action, ids kept ---------------------------------
for index in videos owner_bans; do
    curl -s "localhost:9200/$index/_search?size=10000&filter_path=hits.hits._id,hits.hits._source" \
        | python3 -c '
import json, sys
hits = json.load(sys.stdin).get("hits", {}).get("hits", [])
for h in hits:
    print(json.dumps({"index": {"_index": sys.argv[1], "_id": h["_id"]}}))
    print(json.dumps(h["_source"], ensure_ascii=False))
' "$index" > "$OUT/elasticsearch/$index.ndjson"
    log "elasticsearch $index $(($(wc -l < "$OUT/elasticsearch/$index.ndjson") / 2)) docs"
done

# --- Redis: fold the AOF first so the copy is one rewritten file, not a day of appends -----------
docker exec tt-redis redis-cli BGREWRITEAOF > /dev/null
until [ "$(docker exec tt-redis redis-cli INFO persistence | tr -d '\r' | awk -F: '/aof_rewrite_in_progress/ {print $2}')" = 0 ]; do sleep 1; done
docker run --rm -v tiktok-backend_redis-data:/d:ro -v "$OUT":/o alpine tar czf /o/redis-data.tgz -C /d .
log "redis $(du -h "$OUT/redis-data.tgz" | cut -f1)"

# --- MinIO: the bucket only ---------------------------------------------------------------------
docker run --rm -v tiktok-backend_minio-data:/d:ro -v "$OUT":/o alpine tar czf /o/minio-video-media.tgz -C /d video-media
log "minio $(du -h "$OUT/minio-video-media.tgz" | cut -f1)"

log "done → $OUT ($(du -sh "$OUT" | cut -f1)). Next: scp -r $OUT homebox:~/ then run import-prod.sh there."
