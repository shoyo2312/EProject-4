# One image with every dump tool, so one container runs the whole night in order: dumps first, then
# the off-site copy of what they wrote. On the Cassandra image because cqlsh has to match the server.
FROM cassandra:5.0

# pg_dump from PGDG at the servers' major version: Debian's own is newer, and a newer pg_dump writes
# SET lines a 16 server rejects on restore.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates postgresql-common libgssapi-krb5-2 \
    && /usr/share/postgresql-common/pgdg/apt.postgresql.org.sh -y \
    && apt-get install -y --no-install-recommends postgresql-client-16 \
    && rm -rf /var/lib/apt/lists/*

COPY --from=mongo:7 /usr/bin/mongodump /usr/bin/mongorestore /usr/bin/
COPY --from=pgsty/mc:RELEASE.2026-09-16T00-00-00Z /usr/bin/mc /usr/bin/mc

COPY backup.sh /backup.sh
ENTRYPOINT ["bash", "/backup.sh"]
