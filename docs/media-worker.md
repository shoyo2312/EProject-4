# Media Worker — Transcode, Avatar Mirror & Media Cleanup

Tài liệu cho người **vận hành**, không phải client. `media-worker` (`:8084`) **không có REST API** và không có route ở gateway — nó chỉ là Kafka consumer + producer, thao tác trên MinIO. Client không bao giờ gọi thẳng; nó thấy kết quả gián tiếp qua `video-service` (`status` chuyển `PUBLISHED`/`FAILED`, `hlsUrl`) và `user-service` (`avatarUrl` đổi sang bản MinIO).

## 1. media-worker làm 3 việc

| Việc | Nghe topic | Phát topic | Ghi vào MinIO |
|---|---|---|---|
| Transcode video | `video.video-events` (`VideoPublishedEvent`) | `media.video-transcoded-events` (`VideoTranscodedEvent`) | `hls/{videoId}/source.mp4` |
| Dọn media khi xoá video | `video.video-events` (`VideoDeletedEvent`) | — | xoá `thumbnails/{id}.jpg`, mọi object dưới `hls/{id}/`, và file raw |
| Sao ảnh đại diện social | `auth.social-avatar-events` (`SocialAvatarDiscoveredEvent`) | `media.avatar-events` (`AvatarMirroredEvent`) | `avatars/{userId}.jpg` |

Không có DB, không có bảng inbox/idempotency: mọi thao tác ghi là ghi đè cùng key với cùng nội dung (no-op an toàn khi redeliver). Việc idempotent thật nằm ở consumer phía sau (`video-service` / `user-service`), nơi có bảng inbox.

## 2. Transcode — thực chất là copy, chưa phải HLS thật

`TranscodeServiceImpl` hiện **không chạy ffmpeg**. Nó:

1. Đọc `rawFileUrl` từ `VideoPublishedEvent`, suy ra object key trong bucket (`MediaKeys.objectKey` — hỗ trợ cả `s3://bucket/key` và `https://cdn/.../bucket/key`).
2. `statObject` lấy size → vượt `media.video.max-bytes` (500 MB) → `MediaRejectedException` (từ chối vĩnh viễn).
3. Ký presigned GET URL (300s), `VideoProbe` (JAVE/ffprobe) đọc `durationSeconds` → vượt `media.video.max-duration-seconds` (600s) → `MediaRejectedException`.
4. **Server-side copy** `raw/...` → `hls/{videoId}/source.mp4` (bytes không đi qua heap của worker; 2 GB tốn 1 API call).
5. Trả `VideoTranscodedEvent.success(videoId, thumbnailUrl=null, hlsUrl="{endpoint}/{bucket}/hls/{id}/source.mp4", durationSeconds)`.

Hệ quả:
- **Không có thumbnail** (`thumbnailUrl` luôn null trong event) — cần decode frame, chưa làm. Web client fallback poster của nó.
- `hlsUrl` là một file `.mp4` phẳng, phát qua `<video>` chứ không cần hls.js. Nó nằm dưới prefix `hls/{id}/` để cleanup (list đệ quy prefix đó) vẫn xoá được khi có HLS thật sau này.
- Thay `TranscodeServiceImpl` bằng ffmpeg pipeline khi cần adaptive bitrate / thumbnail thật — không phần nào ngoài class đó biết artifact được sinh thế nào.

### Retry & báo lỗi

`VideoEventConsumer.transcodeWithRetries`: `media.transcode.attempts` (default 3), backoff `media.transcode.retry-backoff-millis` (default 2000ms, **sleep thẳng trên listener thread** — dừng partition; đủ khi một worker gánh cả topic).

- `MediaRejectedException` (quá cỡ / quá dài) → **không retry**, phát `VideoTranscodedEvent.failure(videoId, <thông điệp cho người dùng>)` ngay. Thông điệp này lên thẳng `VideoResponse.failureReason` (vd. `"Video is 12m30s; the maximum is 10m00s."`).
- Exception khác (MinIO blip, probe lỗi) → retry đủ số lần; hết vẫn lỗi → `failure` với thông điệp generic `"Transcoding failed after N attempts. Try uploading the file again."` (KHÔNG lộ chuỗi nội bộ chứa endpoint/bucket/key).
- `FAILED` là **terminal** ở `video-service` — không có gì re-run transcode. Đó là lý do phải retry ở đây thay vì báo hỏng ngay từ lần đầu.

`VideoTranscodedEventProducer` **chờ broker ack** (timeout 30s) rồi mới coi như xong. Vì worker không có state: nếu `send()` fail âm thầm, video kẹt `PROCESSING` vĩnh viễn không ai retry. Ack fail → ném exception → Kafka redeliver `VideoPublishedEvent` → transcode lại (ghi đè cùng key). Publish nằm **ngoài** khối retry của transcode: lỗi Kafka không được ghi `FAILED` lên một video mà media đã nằm sẵn trong bucket.

## 3. Cleanup khi xoá video

`VideoDeletedEvent` → `MediaCleanupServiceImpl.deleteMediaFor(videoId, rawFileUrl)`:
- Xoá `thumbnails/{id}.jpg`.
- List đệ quy `hls/{id}/` và xoá từng object (HLS thật = 1 playlist + nhiều segment, nên phải list chứ không đoán key).
- Xoá file raw — chỉ khi `rawFileUrl` nằm trong bucket cấu hình; không thì **bỏ qua + log warn** (đoán key = xoá nhầm object). Khi bỏ qua, lifecycle rule prefix `raw/` (7 ngày, khai ở `minio-init`) là thứ dọn hộ.
- Lỗi xoá từng object → **log + nuốt**, không ném. Object sót lại là hoá đơn, không phải bug hệ thống thấy được; ném ra chỉ khiến `kafka-lib` retry cả lượt xoá 3 lần rồi park DLT, mất luôn các xoá đã thành công.

`video-service` phát `VideoDeletedEvent` cả cho video **xoá trước khi kịp publish** (file raw đã trong MinIO, event này là thứ duy nhất còn nhắc key đó). Vì vậy cleanup phải no-op êm với `videoId` lạ.

## 4. Sao ảnh đại diện (avatar mirror)

`SocialAvatarDiscoveredEvent` (auth-service phát mỗi lần đăng nhập social có ảnh) → `AvatarMirrorService.mirror(userId, providerUrl)`:
- Chỉ fetch từ host trong `media.avatar.allowed-hosts` (`lh3.googleusercontent.com`, `platform-lookaside.fbsbx.com`, `graph.facebook.com`, `scontent.xx.fbcdn.net`). Host khác → từ chối.
- Chỉ nhận ảnh, tối đa `media.avatar.max-bytes` (5 MB).
- Ghi `avatars/{userId}.jpg` (một key/user, ghi đè — profile trỏ vào URL này).
- Phát `AvatarMirroredEvent(userId, sourceUrl, mirroredUrl)`. `user-service` chỉ ghi đè `avatarUrl` khi nó đang trống hoặc đúng bằng `sourceUrl` — ảnh user tự đặt không bị đụng.

URL không fetch được (hết hạn, 403, không phải ảnh, host lạ) → sau các lần thử của `kafka-lib` thì vào `auth.social-avatar-events.DLT`. Không sao: lần đăng nhập sau phát lại URL mới, profile giữ ảnh cũ trong lúc chờ.

`AvatarMirroredEventProducer` cũng chờ ack 30s, lý do y hệt transcode producer.

## 5. Cấu hình

| Key | Default | Ý nghĩa |
|---|---|---|
| `media.transcode.attempts` | 3 | Số lần thử transcode trước khi báo FAILED |
| `media.transcode.retry-backoff-millis` | 2000 | Nghỉ giữa các lần thử |
| `media.video.max-bytes` | 524288000 (500 MB) | Backstop cho giới hạn `video-service` ký vào POST policy. **Giữ khớp `VIDEO_MAX_BYTES` của video-service** |
| `media.video.max-duration-seconds` | 600 (10 phút) | **Giữ khớp `VIDEO_MAX_DURATION_SECONDS` của video-service** |
| `media.avatar.allowed-hosts` | 4 host Google/Facebook | Allow-list SSRF cho fetch avatar |
| `media.avatar.max-bytes` | 5242880 (5 MB) | |
| `MINIO_ENDPOINT` / `MINIO_ACCESS_KEY` / `MINIO_SECRET_KEY` / `MINIO_BUCKET` | localhost:9000 / minioadmin / … / `video-media` | Credential MinIO **chỉ nằm ở service này** — đó là lý do cleanup chạy ở đây chứ không ở video-service |

## 6. Bố cục object trong bucket (`MediaKeys`)

| Loại | Key | Ai ghi | Ai xoá |
|---|---|---|---|
| Raw upload | `raw/{userId}/{videoId}.mp4` | client (presigned POST từ video-service) | cleanup, hoặc lifecycle 7 ngày |
| Playback | `hls/{videoId}/source.mp4` | media-worker transcode | cleanup |
| (HLS thật, tương lai) | `hls/{videoId}/master.m3u8` + segment | ffmpeg pipeline | cleanup (list prefix) |
| Thumbnail | `thumbnails/{videoId}.jpg` | (chưa sinh) | cleanup |
| Avatar | `avatars/{userId}.jpg` | media-worker mirror **hoặc** user-service `POST /me/avatar` | (không xoá; ghi đè) |

## 7. kafka-lib

`media-worker` **có** dùng `kafka-lib` error handler: consumer lỗi → retry 3 lần → `<topic>.DLT`. Chưa có outbox (không cần — không ghi DB, hai producer tự chờ ack).
