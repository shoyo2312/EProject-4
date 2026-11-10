#!/bin/bash
# Restore db/backup/ into the dev compose stack. Target databases are dropped and recreated
# (Postgres, ClickHouse, Cassandra keyspace); mongorestore --drop replaces each collection.
# Stop the Java services first, otherwise they write into the DB while it is being replaced.
set -euo pipefail
cd "$(dirname "$0")"

PG_USER=${DB_USERNAME:-postgres}
PG_PASS=${DB_PASSWORD:-postgres}
MONGO_USER=${MONGO_USERNAME:-root}
MONGO_PASS=${MONGO_PASSWORD:-password}
CH_USER=${CLICKHOUSE_USER:-default}
CH_PASS=${CLICKHOUSE_PASSWORD:-clickhouse}
KS=interaction_ks

for pair in tt-postgres-auth:auth_db tt-postgres-user:user_db tt-postgres-admin:admin_db; do
    c=${pair%%:*}; db=${pair##*:}
    echo "postgres $db"
    docker exec -e PGPASSWORD="$PG_PASS" "$c" psql -U "$PG_USER" -d postgres -q \
        -c "DROP DATABASE IF EXISTS $db WITH (FORCE)" -c "CREATE DATABASE $db"
    docker exec -i -e PGPASSWORD="$PG_PASS" "$c" pg_restore -U "$PG_USER" -d "$db" --no-owner --no-privileges < "backup/postgres/$db.bak"
done

echo "mongodb"
docker exec -i tt-mongodb mongorestore -u "$MONGO_USER" -p "$MONGO_PASS" --authenticationDatabase admin \
    --gzip --archive --drop --quiet < backup/mongodb/mongodb.archive.gz

echo "cassandra $KS"
docker exec tt-cassandra cqlsh -e "DROP KEYSPACE IF EXISTS $KS"
docker exec -i tt-cassandra cqlsh < "schema/cassandra/$KS.cql"
docker exec tt-cassandra rm -rf /tmp/csv && docker exec tt-cassandra mkdir -p /tmp/csv
docker exec -i tt-cassandra tar -xzf - -C /tmp/csv < "backup/cassandra/$KS.tar.gz"
for f in $(tar -tzf "backup/cassandra/$KS.tar.gz" | grep '\.csv$'); do
    t=$(basename "$f" .csv)
    docker exec tt-cassandra cqlsh -e "COPY $KS.$t FROM '/tmp/csv/$t.csv' WITH HEADER = true" > /dev/null
done

echo "clickhouse analytics_db"
CH=(docker exec -i tt-clickhouse clickhouse-client --user "$CH_USER" --password "$CH_PASS")
"${CH[@]}" -q "DROP DATABASE IF EXISTS analytics_db"
"${CH[@]}" -q "CREATE DATABASE analytics_db"
"${CH[@]}" --multiquery < schema/clickhouse/analytics_db.sql
for f in backup/clickhouse/*.native.gz; do
    t=$(basename "$f" .native.gz)
    gunzip -c "$f" | "${CH[@]}" -q "INSERT INTO analytics_db.$t FORMAT Native"
done
echo "done"
