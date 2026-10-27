#!/bin/bash
# Load an export-prod.sh directory into the production stack on this host (Coolify, docker-compose.prod.yml).
# Every store is REPLACED: this is for a fresh deployment or one whose data you mean to throw away.
#
#   bash import-prod.sh ~/seed-export https://media.nowaa.site
#
# The second argument is MEDIA_PUBLIC_URL of this deployment: the local seed stored media as
# http://localhost:9000/video-media/..., and that prefix is rewritten in Mongo (videos), Postgres
# (avatars) and the search index. Dumps are restored through the `backup` container, which has every
# client tool and password; Redis and MinIO are copied straight into their volumes while stopped.
set -euo pipefail

DIR=${1:?export directory}
NEW=${2:?MEDIA_PUBLIC_URL, e.g. https://media.nowaa.site}
OLD=http://localhost:9000
log() { echo "import: $*"; }

# Coolify names containers <service>-<project>-<stamp> and volumes <project>_<name>.
PROJECT=$(docker ps -a --format '{{.Names}}' | grep '^backup-' | head -1 | cut -d- -f2)
[ -n "$PROJECT" ] || { echo "no backup container found: is the stack deployed?"; exit 1; }
c() { docker ps -a --format '{{.Names}}' | grep "^$1-$PROJECT-" | head -1; }
B=$(c backup)

JAVA="api-gateway auth-service user-service video-service media-worker interaction-service story-service
      recommendation-service chat-service notification-service search-service admin-service analytics-service"

# --- 1. nothing writes while we replace stores ---------------------------------------------------
for s in $JAVA; do docker stop "$(c "$s")" > /dev/null; done
log "java services stopped"

docker exec "$B" mkdir -p /backups/import
docker cp "$DIR/." "$B:/backups/import/"

# --- 2. Postgres ------------------------------------------------------------------------------
for spec in postgres-auth:auth_db postgres-user:user_db postgres-admin:admin_db; do
    IFS=: read -r host db <<< "$spec"
    docker exec "$B" bash -c "PGPASSWORD=\$DB_PASSWORD pg_restore -h $host -U \$DB_USERNAME -d $db --clean --if-exists --no-owner /backups/import/postgres/$db.dump" \
        || log "pg_restore $db reported errors (DROP of objects that did not exist is normal)"
    log "postgres $db"
done

# Sessions, codes and device memory from the laptop must not come along. Outbox rows already
# published are not events anymore; unpublished ones would be re-sent into prod's Kafka, so keep them.
docker exec "$B" bash -c 'PGPASSWORD=$DB_PASSWORD psql -h postgres-auth -U $DB_USERNAME -d auth_db -v ON_ERROR_STOP=1 -q <<SQL
TRUNCATE refresh_tokens, verification_tokens, known_devices, known_device_accounts, auth_security_events;
DELETE FROM outbox_events WHERE published_at IS NOT NULL;
SQL'
docker exec "$B" bash -c "PGPASSWORD=\$DB_PASSWORD psql -h postgres-user -U \$DB_USERNAME -d user_db -v ON_ERROR_STOP=1 -q -c \"UPDATE user_profiles SET avatar_url = replace(avatar_url, '$OLD', '$NEW') WHERE avatar_url LIKE '$OLD%'\""
log "postgres: infra tables cleared, avatars rewritten"

# --- 3. MongoDB -------------------------------------------------------------------------------
docker exec "$B" bash -c 'printf "password: \"%s\"\n" "$MONGO_PASSWORD" > /tmp/m.yml'
MONGO_USER=$(docker exec "$B" printenv MONGO_USERNAME)
for db in video_db story_db chat_db notification_db; do
    docker exec "$B" mongorestore --host mongodb --username "$MONGO_USER" --authenticationDatabase admin \
        --config /tmp/m.yml --gzip --archive="/backups/import/mongo/$db.archive.gz" --drop --quiet
    log "mongo $db"
done
docker exec "$(c mongodb)" sh -c "mongosh -u \"\$MONGO_INITDB_ROOT_USERNAME\" -p \"\$MONGO_INITDB_ROOT_PASSWORD\" --authenticationDatabase admin --quiet video_db --eval '
const old = \"$OLD\", neu = \"$NEW\";
for (const f of [\"hlsUrl\", \"previewUrl\", \"thumbnailUrl\"]) {
  const r = db.videos.updateMany({ [f]: { \$regex: \"^\" + old } }, [{ \$set: { [f]: { \$replaceOne: { input: \"\$\" + f, find: old, replacement: neu } } } }]);
  print(f + \": \" + r.modifiedCount);
}'"
log "mongo: media urls rewritten"

# --- 4. Cassandra (docs/operations.md §3: only into an empty keyspace, COPY FROM adds to counters) --
docker exec "$B" bash -c '
set -e
cqlsh cassandra -e "DROP KEYSPACE IF EXISTS interaction_ks"
cqlsh cassandra -f /backups/import/cassandra/schema.cql
for f in /backups/import/cassandra/*.csv; do
    t=$(basename "$f" .csv)
    cqlsh cassandra -e "COPY interaction_ks.$t FROM '"'"'$f'"'"' WITH HEADER = true" > /dev/null
done'
log "cassandra interaction_ks"

# --- 5. ClickHouse (tables already exist: analytics-service created them on first start) ----------
CH=$(c clickhouse)
for f in "$DIR"/clickhouse/*.native; do
    t=$(basename "$f" .native)
    docker exec -i "$CH" sh -c "clickhouse-client --password \"\$CLICKHOUSE_PASSWORD\" -d analytics_db --query 'INSERT INTO $t FORMAT Native'" < "$f"
done
log "clickhouse $(ls "$DIR"/clickhouse | wc -l) tables"

# --- 6. Elasticsearch: indices exist (search-service made them); rewrite the prefix, then bulk ------
for index in videos owner_bans; do
    sed "s#$OLD#$NEW#g" "$DIR/elasticsearch/$index.ndjson" > "/tmp/$index.ndjson"
    docker cp "/tmp/$index.ndjson" "$B:/backups/import/elasticsearch/$index.rewritten.ndjson"
    docker exec "$B" bash -c "curl -s -u elastic:\$ELASTIC_PASSWORD -H 'Content-Type: application/x-ndjson' \
        --data-binary @/backups/import/elasticsearch/$index.rewritten.ndjson 'http://elasticsearch:9200/_bulk?refresh=true' \
        | grep -o '\"errors\":[a-z]*'"
    log "elasticsearch $index"
done

# --- 7. Redis and MinIO: replace the data dir while the container is stopped ----------------------
R=$(c app-redis)
docker stop "$R" > /dev/null
docker run --rm -v "${PROJECT}_redis-data:/d" -v "$DIR:/o:ro" alpine sh -c 'rm -rf /d/* && tar xzf /o/redis-data.tgz -C /d'
docker start "$R" > /dev/null
log "redis"

M=$(c minio)
docker stop "$M" > /dev/null
docker run --rm -v "${PROJECT}_minio-data:/d" -v "$DIR:/o:ro" alpine sh -c 'rm -rf /d/video-media && tar xzf /o/minio-video-media.tgz -C /d'
docker start "$M" > /dev/null
log "minio video-media"

# --- 8. back up ---------------------------------------------------------------------------------
for s in $JAVA; do docker start "$(c "$s")" > /dev/null; done
docker exec "$B" rm -rf /backups/import
log "java services started. Check: curl -s https://api.nowaa.site/api/v1/videos/feed?size=1 | head -c 300"
log "admin: the laptop's ADMIN account came along with its laptop password; auth-service promotes ADMIN_EMAIL on start but never overwrites a password"
