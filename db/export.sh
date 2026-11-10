#!/bin/bash
# Export schema + data of every database in the dev compose stack into db/.
# Requires `make infra-up` (containers tt-postgres-*, tt-mongodb, tt-cassandra, tt-clickhouse).
#
#   db/schema/   DDL only, readable, diff-friendly       -> commit
#   db/backup/   full dumps (schema + data)              -> restore with db/restore.sh
set -euo pipefail
cd "$(dirname "$0")"

PG_USER=${DB_USERNAME:-postgres}
PG_PASS=${DB_PASSWORD:-postgres}
MONGO_USER=${MONGO_USERNAME:-root}
MONGO_PASS=${MONGO_PASSWORD:-password}
CH_USER=${CLICKHOUSE_USER:-default}
CH_PASS=${CLICKHOUSE_PASSWORD:-clickhouse}
KS=interaction_ks

mkdir -p schema/postgres schema/mongodb schema/cassandra schema/clickhouse \
         backup/postgres backup/mongodb backup/cassandra backup/clickhouse

# ---- PostgreSQL: one container per database ---------------------------------
for pair in tt-postgres-auth:auth_db tt-postgres-user:user_db tt-postgres-admin:admin_db; do
    c=${pair%%:*}; db=${pair##*:}
    echo "postgres $db"
    docker exec -e PGPASSWORD="$PG_PASS" "$c" pg_dump -U "$PG_USER" -d "$db" \
        --schema-only --no-owner --no-privileges > "schema/postgres/$db.sql"
    docker exec -e PGPASSWORD="$PG_PASS" "$c" pg_dump -U "$PG_USER" -d "$db" \
        --no-owner --no-privileges --column-inserts > "backup/postgres/$db.sql"
    docker exec -e PGPASSWORD="$PG_PASS" "$c" pg_dump -U "$PG_USER" -d "$db" -Fc > "backup/postgres/$db.bak"
done

# ---- MongoDB: collections + indexes as a mongosh script, data as mongodump --
echo "mongodb"
MONGO_AUTH=(-u "$MONGO_USER" -p "$MONGO_PASS" --authenticationDatabase admin --quiet)
docker exec tt-mongodb mongosh "${MONGO_AUTH[@]}" --eval '
  const skip = ["admin","config","local"];
  db.adminCommand({listDatabases:1}).databases.map(d=>d.name).filter(n=>!skip.includes(n)).sort().forEach(name => {
    const d = db.getSiblingDB(name);
    print(`// ---- ${name} ----`);
    print(`db = db.getSiblingDB(${JSON.stringify(name)});`);
    d.getCollectionNames().sort().forEach(c => {
      print(`db.createCollection(${JSON.stringify(c)});`);
      d.getCollection(c).getIndexes().filter(i => i.name !== "_id_").forEach(i => {
        const {v, key, name, ns, ...opts} = i;
        print(`db.getCollection(${JSON.stringify(c)}).createIndex(${JSON.stringify(key)}, ${JSON.stringify({name, ...opts})});`);
      });
    });
    print("");
  });' > schema/mongodb/create-collections.js
docker exec tt-mongodb mongodump "${MONGO_AUTH[@]}" --gzip --archive > backup/mongodb/mongodb.archive.gz

# ---- Cassandra: schema from the live keyspace, data as CSV --------------------
echo "cassandra $KS"
docker exec tt-cassandra cqlsh -e "DESCRIBE KEYSPACE $KS" > "schema/cassandra/$KS.cql"
tables=$(docker exec tt-cassandra cqlsh -e "SELECT table_name FROM system_schema.tables WHERE keyspace_name='$KS'" \
    | sed -nE 's/^ *([a-z0-9_]+) *$/\1/p' | grep -vx table_name)
docker exec tt-cassandra rm -rf /tmp/csv && docker exec tt-cassandra mkdir -p /tmp/csv
for t in $tables; do
    docker exec tt-cassandra cqlsh -e "COPY $KS.$t TO '/tmp/csv/$t.csv' WITH HEADER = true" > /dev/null
done
docker exec tt-cassandra tar -czf - -C /tmp/csv . > "backup/cassandra/$KS.tar.gz"

# ---- ClickHouse: DDL + one Native-format file per table -----------------------
echo "clickhouse analytics_db"
CH=(docker exec tt-clickhouse clickhouse-client --user "$CH_USER" --password "$CH_PASS")
"${CH[@]}" -q "SELECT name FROM system.tables WHERE database='analytics_db' ORDER BY name FORMAT TSVRaw" | while read -r t; do
    "${CH[@]}" -q "SHOW CREATE TABLE analytics_db.$t FORMAT TSVRaw"; echo ";"; echo
done > schema/clickhouse/analytics_db.sql
"${CH[@]}" -q "SELECT name FROM system.tables WHERE database='analytics_db' FORMAT TSVRaw" | while read -r t; do
    "${CH[@]}" -q "SELECT * FROM analytics_db.$t FORMAT Native" | gzip > "backup/clickhouse/$t.native.gz"
done

echo "done -> $(du -sh backup | cut -f1) in backup/, schema in schema/"
