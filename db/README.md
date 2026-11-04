# db/ — Database của project

Project dùng 4 loại database, mỗi service một database riêng (database-per-service).
Thư mục này gom **schema** (DDL, đọc được) và **backup** (schema + dữ liệu) của tất cả,
xuất từ stack `docker-compose.yml` bằng `./export.sh`.

| Database | Service | Schema | Backup |
|---|---|---|---|
| PostgreSQL `auth_db` | auth-service | `schema/postgres/auth_db.sql` | `backup/postgres/auth_db.sql` (script có INSERT), `auth_db.bak` (pg_dump custom format) |
| PostgreSQL `user_db` | user-service | `schema/postgres/user_db.sql` | `backup/postgres/user_db.sql`, `user_db.bak` |
| PostgreSQL `admin_db` | admin-service | `schema/postgres/admin_db.sql` | `backup/postgres/admin_db.sql`, `admin_db.bak` |
| MongoDB `video_db`, `story_db`, `chat_db`, `notification_db` | video / story / chat / notification-service | `schema/mongodb/create-collections.js` (collection + index) | `backup/mongodb/mongodb.archive.gz` (mongodump) |
| Cassandra keyspace `interaction_ks` | interaction-service | `schema/cassandra/interaction_ks.cql` | `backup/cassandra/interaction_ks.tar.gz` (CSV mỗi table) |
| ClickHouse `analytics_db` | analytics-service | `schema/clickhouse/analytics_db.sql` | `backup/clickhouse/<table>.native.gz` |

Không có `.mdb`/`.bak` của SQL Server vì project không dùng SQL Server/Access.
File `.bak` ở đây là định dạng custom của `pg_dump -Fc`, mở bằng `pg_restore`.

Redis, Kafka, Elasticsearch, MinIO không có trong đây: Redis là cache/token hết hạn trong ngày,
Kafka chỉ chuyển event (bản gốc nằm trong bảng `outbox_events`), Elasticsearch dựng lại từ
`VideoPublishedEvent`, MinIO là file media chứ không phải database.

## Nguồn gốc schema trong code

- PostgreSQL: Flyway migration `services/{auth,user,admin}-service/src/main/resources/db/migration/V*.sql`
  (file ở đây là kết quả sau khi chạy hết migration).
- MongoDB: annotation `@Document`/`@Indexed`/`@CompoundIndex` trên entity của từng service.
- Cassandra: `scripts/cassandra-init.cql`.
- ClickHouse: `services/analytics-service/src/main/resources/schema.sql`.

## Lệnh

```bash
make infra-up        # cần container tt-postgres-*, tt-mongodb, tt-cassandra, tt-clickhouse
./db/export.sh       # xuất lại schema + backup từ DB đang chạy
./db/restore.sh      # XOÁ database hiện tại và nạp lại từ backup/ (dừng service Java trước)
```

Restore một database lẻ:

```bash
# PostgreSQL (từ .bak)
docker exec -i tt-postgres-auth pg_restore -U postgres -d auth_db --clean --if-exists --no-owner < db/backup/postgres/auth_db.bak
# PostgreSQL (từ .sql, DB phải trống)
docker exec -i tt-postgres-auth psql -U postgres -d auth_db < db/backup/postgres/auth_db.sql
# MongoDB
docker exec -i tt-mongodb mongorestore -u root -p password --authenticationDatabase admin --gzip --archive --drop < db/backup/mongodb/mongodb.archive.gz
# Chỉ tạo collection + index MongoDB (không có dữ liệu)
docker exec -i tt-mongodb mongosh -u root -p password --authenticationDatabase admin < db/schema/mongodb/create-collections.js
```

## Lưu ý

`backup/` là dữ liệu thật của môi trường dev: email, hash mật khẩu, refresh token (đã hash),
tin nhắn. Chỉ dùng để nộp/demo, không đưa lên repo public.
