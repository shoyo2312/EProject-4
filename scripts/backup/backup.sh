#!/bin/bash
# Daily copies of every store that holds something nothing else can rebuild, kept BACKUP_KEEP_DAYS on
# the backups volume and, when BACKUP_S3_URL is set, copied off this machine. Restore steps are in
# docs/operations.md.
#
#   Postgres  accounts, profiles, follows, admin audit           pg_dump, one file per database
#   MongoDB   videos, stories, chat, notifications              mongodump, one gzipped archive
#   Cassandra likes, comments, counters                          schema + COPY TO, one tarball
#   Elastic   the search index: no job rebuilds it from the      SLM snapshots it takes itself;
#             services that own the documents                    this only keeps the policy in place
#   MinIO     the media                                          mirrored off-site only
#
# Left out on purpose: Redis (revocations and counters that expire within the day), Kafka (anything
# unpublished is still in an outbox), ClickHouse (analytics; the feed keeps working without it).
#
# Not `set -e`: one store being down must not cost the night's copy of the others.
set -uo pipefail

OUT=/backups
KEEP=${BACKUP_KEEP_DAYS:-7}
DB=interaction_ks
ES=http://elasticsearch:9200

log() { echo "backup: $*"; }

failures=0
step() {
    local name=$1
    shift
    if "$@"; then
        log "$name ok"
    else
        log "$name FAILED"
        failures=$((failures + 1))
    fi
}

# Written to .part and renamed once complete, so a dump cut short is never mistaken for one that
# finished, by a restore or by the off-site copy (which skips .part).
postgres() {
    local host=$1 db=$2 dir=$OUT/postgres/$2
    mkdir -p "$dir"
    PGPASSWORD=$DB_PASSWORD pg_dump -h "$host" -U "$DB_USERNAME" -d "$db" -Fc -f "$dir/$STAMP.dump.part" \
        && mv "$dir/$STAMP.dump.part" "$dir/$STAMP.dump"
}

# The password through a config file, not argv, where anyone running ps in this container reads it.
mongo() {
    local config
    config=$(mktemp)
    printf 'password: "%s"\n' "$MONGO_PASSWORD" > "$config"
    mkdir -p "$OUT/mongo"
    mongodump --host mongodb --username "$MONGO_USERNAME" --authenticationDatabase admin --config "$config" \
        --gzip --archive="$OUT/mongo/$STAMP.archive.gz.part" --quiet \
        && mv "$OUT/mongo/$STAMP.archive.gz.part" "$OUT/mongo/$STAMP.archive.gz"
    local status=$?
    rm -f "$config"
    return $status
}

# COPY TO rather than nodetool snapshot, which only runs inside the Cassandra container. Fine at
# this size; a keyspace of hundreds of millions of rows wants snapshots and sstableloader instead.
cassandra() {
    local work=$OUT/cassandra/$STAMP.part tables table
    mkdir -p "$work"
    cqlsh cassandra -e "DESCRIBE KEYSPACE $DB" > "$work/schema.cql" || return 1
    tables=$(cqlsh cassandra -e "SELECT table_name FROM system_schema.tables WHERE keyspace_name = '$DB'" \
        | sed -n 's/^ *\([a-z0-9_]\+\) *$/\1/p' | grep -vx table_name) || return 1
    for table in $tables; do
        cqlsh cassandra -e "COPY $DB.$table TO '$work/$table.csv' WITH HEADER = true" > /dev/null || return 1
    done
    tar -czf "$OUT/cassandra/$STAMP.tar.gz" -C "$work" . && rm -rf "$work"
}

# Elasticsearch writes its own snapshots into the es-snapshots volume on its own schedule and
# expires them itself (SLM). Both PUTs replace what is there, so this only keeps them as written.
elasticsearch() {
    curl -sf -u "elastic:$ELASTIC_PASSWORD" -X PUT "$ES/_snapshot/nightly" -H 'Content-Type: application/json' \
        -d '{"type":"fs","settings":{"location":"/usr/share/elasticsearch/snapshots"}}' > /dev/null \
    && curl -sf -u "elastic:$ELASTIC_PASSWORD" -X PUT "$ES/_slm/policy/nightly" -H 'Content-Type: application/json' \
        -d "{\"schedule\":\"0 30 2 * * ?\",\"name\":\"<nightly-{now/d}>\",\"repository\":\"nightly\",
             \"config\":{\"include_global_state\":false},
             \"retention\":{\"expire_after\":\"${KEEP}d\",\"min_count\":3,\"max_count\":$((KEEP * 2))}}" > /dev/null
}

# Never --remove: after a lost disk this would mirror an empty volume, and delete the only copy left.
# Old copies off-site expire by the bucket's own lifecycle, which the provider keeps.
offsite() {
    mc alias set offsite "$BACKUP_S3_URL" "$BACKUP_S3_ACCESS_KEY" "$BACKUP_S3_SECRET_KEY" > /dev/null || return 1
    mc alias set media http://minio:9000 backup "$MINIO_BACKUP_SECRET_KEY" > /dev/null || return 1
    # stdout is a line per object copied; errors still reach the log on stderr.
    mc mirror --quiet --overwrite --exclude "*.part" "$OUT" "offsite/$BACKUP_S3_BUCKET/dumps" > /dev/null \
        && mc mirror --quiet --overwrite /es-snapshots "offsite/$BACKUP_S3_BUCKET/elasticsearch" > /dev/null \
        && mc mirror --quiet --overwrite "media/$MINIO_BUCKET" "offsite/$BACKUP_S3_BUCKET/media" > /dev/null
}

while true; do
    STAMP=$(date -u +%Y%m%dT%H%M%SZ)
    failures=0

    step "postgres auth_db" postgres postgres-auth auth_db
    step "postgres user_db" postgres postgres-user user_db
    step "postgres admin_db" postgres postgres-admin admin_db
    step "mongodb" mongo
    step "cassandra $DB" cassandra
    step "elasticsearch snapshot policy" elasticsearch
    find "$OUT/postgres" "$OUT/mongo" "$OUT/cassandra" -type f -mtime +"$KEEP" -delete 2> /dev/null

    if [ -n "${BACKUP_S3_URL:-}" ]; then
        step "off-site copy" offsite
    else
        log "WARNING: no BACKUP_S3_URL, so every copy is on the same disk as the data it is a copy of"
    fi

    # What the healthcheck reads: the container turns unhealthy when a night goes by without one.
    if [ "$failures" -eq 0 ]; then
        date -u +%s > /tmp/last-success
    fi
    log "done at $STAMP, $failures failed"
    sleep 86400
done
