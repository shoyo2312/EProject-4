# Vận hành production

Tài liệu cho người **deploy và trực** `docker-compose.prod.yml` (Coolify). Client không cần đọc.

## 1. Biến môi trường

Ngoài các biến đã có (`JWT_SECRET`, `DB_PASSWORD`, …), production cần thêm các biến dưới đây. Thiếu một biến `:?` thì
Coolify dừng deploy ngay, không có service nào chạy nửa vời.

Sinh giá trị bằng `openssl rand -hex 24`. Hex chỉ có chữ và số: `KAFKA_PASSWORD` bị ghép vào một chuỗi JAAS, còn `$`
trong giá trị sẽ bị compose hiểu là biến.

| Biến | Bắt buộc | Ai dùng |
|---|---|---|
| `REDIS_PASSWORD` | có | `app-redis` (`requirepass`) và 6 service dùng Redis (gateway, auth, video, interaction, recommendation, chat) |
| `KAFKA_PASSWORD` | có | broker (listener `CLIENT`) và mọi service Java (`x-java-env`) |
| `ELASTIC_PASSWORD` | có | user `elastic` của Elasticsearch, `search-service`, `backup` |
| `MINIO_VIDEO_SECRET_KEY`, `MINIO_STORY_SECRET_KEY`, `MINIO_USER_SECRET_KEY`, `MINIO_WORKER_SECRET_KEY`, `MINIO_BACKUP_SECRET_KEY` | có, ≥ 8 ký tự | mỗi service một account MinIO riêng, do `minio-init` tạo |
| `ALERT_EMAIL` | không (mặc định `ADMIN_EMAIL`) | nơi Alertmanager gửi cảnh báo |
| `BACKUP_S3_URL`, `BACKUP_S3_ACCESS_KEY`, `BACKUP_S3_SECRET_KEY` | không, nhưng **nên có** (§3) | bản sao off-site |
| `BACKUP_S3_BUCKET` (`tiktok-backups`), `BACKUP_KEEP_DAYS` (`7`) | không | |

## 2. Ai giữ mật khẩu nào

Mọi store chỉ nghe trong mạng nội bộ, không publish port nào. Mật khẩu không thay cho việc đó; nó chặn một container
đã bị chiếm (ffmpeg ở media-worker parse mọi file người dùng upload) đi tiếp sang chỗ khác.

- **Redis**: một mật khẩu dùng chung. Ai `DEL auth:blacklist:*` được là gỡ revoke mọi token bị đánh cắp, ai
  `FLUSHALL` được là gỡ mọi ban và rate limit. media-worker không dùng Redis nên không có mật khẩu này.
- **Kafka**: SASL/PLAIN, một account `app` dùng chung, chưa có ACL. Listener `LOCAL` (plaintext, chỉ `127.0.0.1` trong
  container broker) dành cho controller connection của broker và healthcheck.
- **Elasticsearch**: security bật, chỉ `search-service` và `backup` có mật khẩu.
- **MinIO**: root chỉ còn `minio-init` giữ. Mỗi service có account riêng, chỉ chạm được prefix của mình
  (`scripts/minio/policies/`):

  | Account | Quyền |
  |---|---|
  | `video-service` | ghi + đọc `raw/`, đọc `quarantine/` |
  | `story-service` | ghi + đọc `stories/` |
  | `user-service` | ghi `avatars/`; ghi, đọc, xoá `exports/` |
  | `media-worker` | toàn bộ bucket, không có quyền admin, không chạm bucket khác |
  | `backup` | chỉ đọc toàn bộ bucket |

  Đọc được prefix là điều kiện để object không tồn tại trả 404 `NoSuchKey` thay vì 403. `video-service` và
  `story-service` dựa vào mã đó để phân biệt "chưa upload" với "storage chết". Thêm prefix mới cho một service thì
  sửa policy của nó, không thì gặp 403.

**Đổi mật khẩu**: sửa env trong Coolify rồi deploy lại cả stack. Riêng Kafka, broker và client phải đổi cùng lúc,
nên sẽ gián đoạn vài giây. Riêng `ELASTIC_PASSWORD`: một khi mật khẩu đã được đổi qua security API thì giá trị của API
thắng, giá trị trong env bị bỏ qua. Vì vậy hãy đổi qua API trước
(`POST /_security/user/elastic/_password`), rồi mới cập nhật env.

## 3. Backup

Container `backup` (`scripts/backup/backup.sh`) chạy lúc khởi động, sau đó cứ 24 giờ một lần. Mỗi lượt chạy lần lượt:

| Store | Cách làm | Lưu ở |
|---|---|---|
| Postgres `auth_db`, `user_db`, `admin_db` | `pg_dump -Fc` | `/backups/postgres/{db}/{stamp}.dump` |
| MongoDB (video, story, chat, notification) | `mongodump --gzip --archive` | `/backups/mongo/{stamp}.archive.gz` |
| Cassandra `interaction_ks` | `DESCRIBE` + `COPY TO` từng bảng | `/backups/cassandra/{stamp}.tar.gz` |
| Elasticsearch | SLM tự chụp snapshot lúc 02:30 và tự xoá bản cũ; `backup` chỉ giữ policy | volume `es-snapshots` |
| MinIO (media) | chỉ mirror off-site | |

Bản dump giữ `BACKUP_KEEP_DAYS` ngày trên volume `backups`. Có `BACKUP_S3_URL` thì toàn bộ, kể cả media, được
`mc mirror` sang bucket off-site (R2, B2, S3…).

- **Không có off-site thì backup nằm cùng ổ đĩa với dữ liệu**: mất ổ là mất cả hai. Log mỗi đêm sẽ in `WARNING`.
- Mirror **không bao giờ** dùng `--remove`. Sau khi mất ổ, volume trống mà mirror kèm `--remove` thì sẽ xoá luôn bản
  off-site duy nhất còn lại. Vì vậy hãy đặt lifecycle rule cho bucket off-site ngay tại nhà cung cấp.
- Hệ quả: media bị xoá ở đây (xoá tài khoản, purge video) **vẫn còn** ở off-site tới khi lifecycle bên đó xoá. Nếu
  có cam kết xoá dữ liệu người dùng trong N ngày thì đặt lifecycle ≤ N.
- **Không backup**: Redis (revocation và counter đều hết hạn trong ngày), Kafka (event chưa publish vẫn nằm trong
  outbox), ClickHouse (analytics; feed vẫn chạy khi thiếu nó).
- **Theo dõi**: container chuyển `unhealthy` khi quá 26 giờ không có lượt nào mà mọi bước đều thành công. Xem
  `docker logs <backup>`: mỗi bước một dòng `ok` hoặc `FAILED`.

### Restore

Dừng service sở hữu store trước khi restore, để không có gì ghi chen vào giữa. Tất cả lệnh dưới đây chạy trong
container backup (`docker exec -it $(docker ps -qf name=backup) bash`), vì nó có sẵn mọi công cụ, mọi mật khẩu cần
thiết và volume `/backups`.

```bash
# Postgres: một database
PGPASSWORD=$DB_PASSWORD pg_restore -h postgres-auth -U "$DB_USERNAME" -d auth_db --clean --if-exists \
    /backups/postgres/auth_db/<stamp>.dump

# MongoDB: cả archive, hoặc thêm --nsInclude 'video_db.*' để chỉ restore một database
printf 'password: "%s"\n' "$MONGO_PASSWORD" > /tmp/m.yml
mongorestore --host mongodb --username "$MONGO_USERNAME" --authenticationDatabase admin --config /tmp/m.yml \
    --gzip --archive=/backups/mongo/<stamp>.archive.gz --drop

# Cassandra: chỉ vào keyspace trống. COPY FROM cộng dồn vào bảng counter, không ghi đè
mkdir /tmp/r && tar -xzf /backups/cassandra/<stamp>.tar.gz -C /tmp/r
cqlsh cassandra -e "DROP KEYSPACE IF EXISTS interaction_ks"
cqlsh cassandra -f /tmp/r/schema.cql
for f in /tmp/r/*.csv; do
    cqlsh cassandra -e "COPY interaction_ks.$(basename "$f" .csv) FROM '$f' WITH HEADER = true"
done

# Elasticsearch: xem danh sách snapshot, xoá index cần khôi phục, rồi restore
curl -u "elastic:$ELASTIC_PASSWORD" elasticsearch:9200/_snapshot/nightly/_all
curl -u "elastic:$ELASTIC_PASSWORD" -X DELETE elasticsearch:9200/videos
curl -u "elastic:$ELASTIC_PASSWORD" -X POST -H 'Content-Type: application/json' \
    "elasticsearch:9200/_snapshot/nightly/<snapshot>/_restore?wait_for_completion=true" -d '{"indices":"videos"}'
```

**Khi mất ổ đĩa**: kéo bản off-site về trước, rồi mới làm các bước trên.

- Dump: `mc mirror offsite/<bucket>/dumps /backups`.
- Snapshot ES: container backup mount volume `es-snapshots` chỉ đọc, nên hãy kéo về bằng một container `mc` tạm
  mount volume đó ở chế độ ghi:
  `docker run --rm -v <project>_es-snapshots:/s --entrypoint bash pgsty/mc:… -c "mc alias set offsite …; mc mirror offsite/<bucket>/elasticsearch /s"`.
- Media: `mc mirror offsite/<bucket>/media tt/video-media`, dùng account root.

Lệnh restore của từng store đã được thử trên stack dev: restore vào database/keyspace tạm rồi so số bản ghi với bản
gốc, ES thì xoá index rồi restore từ snapshot. Chiều kéo bản off-site về thì chưa được thử. Hãy diễn tập cả quy trình
trên một máy khác trước khi thật sự phải dùng đến.

## 4. Monitoring

- **Prometheus** scrape `/actuator/prometheus` của 13 service Java mỗi 30 giây, giữ dữ liệu 15 ngày. Cấu hình ở
  `scripts/monitoring/prometheus.yml`.
- **Alertmanager** gửi mail qua tài khoản Resend mà auth-service đang dùng, tới `ALERT_EMAIL`. Nhắc lại sau mỗi 4 giờ
  nếu vấn đề vẫn còn, và báo khi đã hết.

| Alert | Khi nào | Vì sao không có gì khác báo |
|---|---|---|
| `ServiceDown` | một service không trả lời scrape quá 2 phút | |
| `OutboxStuck` | event cũ nhất trong outbox chờ broker quá 5 phút | dispatcher chỉ thấy batch nó được giao: poller chết trông y hệt outbox rảnh |
| `DeadLetters` | có record vào `<topic>.DLT` | không gì tự đọc lại DLT: mỗi record ở đó là một event mọi consumer đều bỏ lỡ |
| `ServerErrors` | hơn 5% request trả 5xx trong 10 phút | |

Hai container này không có domain và không có đăng nhập. Muốn xem UI thì mở SSH tunnel tới IP nội bộ của container:

```bash
ssh <server> "docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' \$(docker ps -qf name=prometheus)"
ssh -L 9090:<ip>:9090 <server>     # rồi mở http://localhost:9090; Alertmanager là cổng 9093
```

## 5. Giới hạn còn lại

- **Kafka**:
  - Mọi service dùng chung một account và chưa có ACL, nên một service bị chiếm vẫn ghi được vào mọi topic.
  - ZooKeeper không có mật khẩu.
  - Bước tiếp theo nếu cần: account riêng cho từng service và bật authorizer.
- **DLT trên prod**: `make dlt-list` / `make dlt-replay` (`DltReplay`) chạy từ máy local và không biết SASL. Trên prod,
  có thể xem DLT bằng listener `LOCAL` ngay trong container broker:
  `kafka-console-consumer --bootstrap-server 127.0.0.1:9092 --topic <topic>.DLT --from-beginning`.
- **Elasticsearch**: `search-service` đang dùng user `elastic` (superuser). Nên đổi sang một role chỉ có quyền trên
  các index của nó.
- **Cassandra**: backup bằng `COPY TO`, ổn ở quy mô hiện tại. Khi lên tới hàng trăm triệu row thì cần
  `nodetool snapshot` và `sstableloader`.
- **story-service chạy đúng một container**: `SNOWFLAKE_NODE_ID` cố định, và compose dừng bản cũ rồi mới chạy bản mới.
  Muốn chạy nhiều bản thì mỗi bản cần một node id riêng. Lần deploy đầu tiên có thông báo story (`StoryActivatedEvent`)
  còn phải dừng hết bản cũ trước khi chạy bản mới. Lý do: bản cũ không ghi `activatedAt`, nên story nó duyệt trong lúc
  hai bản chạy song song sẽ không bao giờ được báo cho follower.
