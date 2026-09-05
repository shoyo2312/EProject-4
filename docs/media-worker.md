# Media Worker — Transcode, Avatar Mirror & Media Cleanup

Tài liệu cho người **vận hành**, không phải client. `media-worker` (`:8084`) **không có REST API** và không có route ở gateway — nó chỉ là Kafka consumer + producer, thao tác trên MinIO. Client không bao giờ gọi thẳng; nó thấy kết quả gián tiếp qua `video-service` (`status` chuyển `PUBLISHED`/`FAILED`, `hlsUrl`) và `user-service` (`avatarUrl` đổi sang bản MinIO).

## 1. media-worker làm 3 việc

| Việc | Nghe topic | Phát topic | Ghi vào MinIO |
|---|---|---|---|
| Transcode video | `video.video-events` (`VideoPublishedEvent`) | `media.video-transcoded-events` (`VideoTranscodedEvent`) | `hls/{videoId}/source.mp4` + `thumbnails/{videoId}.jpg` + `previews/{videoId}.webp` |
| Dọn media khi xoá video | `video.video-events` (`VideoDeletedEvent`) | — | xoá `thumbnails/{id}.jpg`, `previews/{id}.webp`, mọi object dưới `hls/{id}/`, và file raw |
| Sao ảnh đại diện social | `auth.social-avatar-events` (`SocialAvatarDiscoveredEvent`) | `media.avatar-events` (`AvatarMirroredEvent`) | `avatars/{userId}.jpg` |

Không có DB, không có bảng inbox/idempotency: mọi thao tác ghi là ghi đè cùng key với cùng nội dung (no-op an toàn khi redeliver). Việc idempotent thật nằm ở consumer phía sau (`video-service` / `user-service`), nơi có bảng inbox.

## 2. Transcode — normalize hoặc faststart remux, chưa phải HLS

`TranscodeServiceImpl`:

1. Đọc `rawFileUrl` từ `VideoPublishedEvent`, suy ra object key trong bucket (`MediaKeys.objectKey` — hỗ trợ cả `s3://bucket/key` và `https://cdn/.../bucket/key`).
2. `statObject` lấy size → vượt `media.video.max-bytes` (500 MB) → `MediaRejectedException` (từ chối vĩnh viễn). Check **trước** khi tải để file quá cỡ chỉ tốn một HEAD.
3. `downloadObject` về thư mục tạm, `VideoProbe` (JAVE parse output `ffmpeg -i`) đọc duration + codec + kích thước từ file local. Duration vượt `media.video.max-duration-seconds` (600s) → `MediaRejectedException`.
4. **Chọn một trong hai đường** theo `ProbedVideo.needsNormalizing()` (xem §2.1). Upload kết quả lên `hls/{videoId}/source.mp4`.
5. **Thumbnail**: `ffmpeg -ss {t} -i source -frames:v 1 -vf scale=-2:720 -q:v 3` với `t = min(1, duration/2)` giây. Upload lên `thumbnails/{videoId}.jpg`.
6. **Animated preview** (§2.2): `ffmpeg -ss {t} -t 3 -i source -vf fps=8,scale=-2:240 -an -c:v libwebp_anim -loop 0 -q:v 60` — cùng mốc `t`. Upload lên `previews/{videoId}.webp`, content-type `image/webp`.
7. Trả `VideoTranscodedEvent.success(videoId, thumbnailUrl, previewUrl, hlsUrl="{endpoint}/{bucket}/hls/{id}/source.mp4", durationSeconds)`.
8. `finally`: xoá thư mục tạm — trên mọi đường ra, kể cả khi lỗi.

### 2.1. Hai đường: rẻ và đắt

`ProbedVideo.needsNormalizing()` trả `false` khi file **đã** ở dạng browser phát được: video `h264`, audio `aac` (hoặc không có audio), và cạnh dài ≤ 1280 **và** cạnh ngắn ≤ 720.

| | Điều kiện | Lệnh | Chi phí CPU |
|---|---|---|---|
| **Faststart remux** (rẻ) | đã đúng dạng | `-c copy -movflags +faststart -f mp4` | phẳng theo độ dài video |
| **Normalize** (đắt) | mọi trường hợp còn lại | `-vf scale='min(iw,if(gt(iw,ih),1280,720))':-2 -c:v libx264 -preset veryfast -crf 23 -profile:v high -pix_fmt yuv420p -c:a aac -b:a 128k -ac 2 -movflags +faststart` | tỉ lệ thuận với độ dài video |

Normalize bắt các trường hợp thật: HEVC từ iPhone, AV1, VP8/VP9 từ MediaRecorder của trình duyệt, quay 4K từ điện thoại.

**Auto-rotation nằm trong normalize, không phải bước riêng.** `iw`/`ih` trong filter là kích thước **sau khi** ffmpeg áp display matrix của container, nên:
- Cap 720p rơi đúng vào video như người xem thấy: cạnh dài 1280, cạnh ngắn 720. Video dọc ra `720x1280`, ngang ra `1280x720`.
- Góc quay được **nướng thẳng vào pixel** và matrix bị gỡ khỏi output — không có chuyện player xoay lần thứ hai. `JaveFfmpegTest` kiểm cả hai: normalize file `1920x1080` gắn rotation 90 ra `720x1280`, và normalize **hai lần** vẫn ra `720x1280`.
- `min(iw, ...)` là thứ chặn upscale: clip `640x360` giữ nguyên `640x360`, không phình lên 720p cho không.

Đường rẻ **không** gỡ rotation — file giữ nguyên display matrix và trình duyệt tự xoay đúng (`<video>` honor matrix từ lâu). Thumbnail cũng được ffmpeg autorotate.

### 2.2. Animated preview cho hover

WebP động 3 giây, 8 fps, cao 240px, không audio. Con số nằm ở đó vì đây là thứ feed kéo về **mỗi card**, không phải thứ xem toàn màn hình: đủ chuyển động để biết video nói gì, đủ nhỏ để không cần loading state. Clip 3 giây test ra khoảng 8 KB.

Rotation được ffmpeg autorotate như thumbnail, nên preview của video dọc ra khung dọc.

`libwebp_anim` có trong bản ffmpeg JAVE bundle, nhưng **không được giả định**: nếu build trên platform nào đó thiếu encoder, `animatedPreview` trả `false` → `previewUrl` null → client fallback về `thumbnailUrl`. Không có gì hỏng.

### 2.3. Hai đường hỏng khác nhau, có chủ đích

- **Remux fail** → upload nguyên file gốc, log `info`. An toàn vì nhánh này chỉ chạy trên file **đã biết là phát được**; mất tối ưu khởi động, không mất video.
- **Normalize fail** → **throw**. Fallback ở đây nghĩa là đặt một file HEVC/AV1 vào playback key và đưa người xem một video im lặng không phát được — tệ hơn `FAILED` mà ít nhất người upload còn nhìn thấy. Consumer retry 3 lần rồi báo `FAILED` với thông điệp generic.
- **Không decode được frame** → `thumbnailUrl` null, web client fallback poster của nó.
- **Không dựng được preview** → `previewUrl` null, hover hiện thumbnail tĩnh.

Không được biến một video xem được thành `FAILED` chỉ vì thiếu ảnh.

### 2.4. Chi phí và giới hạn

- **Disk**: file đi qua disk của worker (bản copy-only đầu tiên không chuyển byte nào ra khỏi MinIO). Dời moov atom = viết lại container, MinIO không có thao tác server-side nào làm được. Dự trù **~2× kích thước upload** disk tạm cho mỗi transcode chạy song song.
- **Storage**: 1 file playback + 1 thumbnail + 1 preview (~10 KB) cho mỗi video. Không có ABR ladder, không có segment.
- Chưa làm: HLS segment (`master.m3u8` + `.ts`), ABR ladder, watermark, loudness normalization, frame sampling cho AI moderation.

### 2.5. Binary ffmpeg và timeout

Không có bước Dockerfile nào. `JaveFfmpeg` dùng chính binary mà JAVE2 (`jave-all-deps`) đã giải nén để probe, qua `DefaultFFMPEGLocator.getExecutablePath()` — dependency native duy nhất của worker vẫn là cái build đã ship sẵn cho mọi platform. **Không có `ffprobe`** trong bundle đó; JAVE lấy metadata bằng cách parse output của `ffmpeg -i`.

| Lệnh | Timeout | Lý do |
|---|---|---|
| faststart remux, still frame, animated preview | 120s | copy stream / decode 1 frame — chặn bởi disk, không theo độ dài video |
| normalize | 600s | decode + encode từng frame — có tỉ lệ với độ dài video |

Hết timeout → `destroyForcibly()`. Diagnostics của ffmpeg ghi ra **file tạm**, không phải pipe: pipe không ai đọc sẽ đầy buffer và ffmpeg block vĩnh viễn ở lệnh write — không timeout nào trên `waitFor` bắt được.

### Retry & báo lỗi

`VideoEventConsumer.transcodeWithRetries`: `media.transcode.attempts` (default 3), backoff `media.transcode.retry-backoff-millis` (default 2000ms, **sleep thẳng trên listener thread** — dừng partition; đủ khi một worker gánh cả topic).

**Poll interval phải khớp với timeout normalize.** Normalize chạy trên listener thread, một message có thể giữ partition vài phút. Để mặc định 5 phút của Spring thì broker coi consumer chết giữa lúc encode → rebalance → redeliver đúng video đó → encode lại từ đầu, vòng sau chậm hơn vòng trước. Vì vậy `application.yml` đặt `max.poll.interval.ms: 3600000` (1 giờ) và `max-poll-records: 1`: 1 giờ phủ được worst case mà timeout cho phép — 3 lần transcode × 600s cộng download và backoff.

*ponytail: đây là bản rẻ của việc đẩy transcode ra khỏi listener thread. Khi cần worker thứ hai chạy tiếp qua một video chậm thì đó mới là chỗ phải sửa.*

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
| `spring.kafka.consumer.properties.max.poll.interval.ms` | 3600000 (1 giờ) | Phải lớn hơn `attempts × 600s` + download; xem §Retry |
| `spring.kafka.consumer.max-poll-records` | 1 | Một batch không được xếp chồng nhiều transcode vào cùng một poll |
| `media.avatar.allowed-hosts` | 4 host Google/Facebook | Allow-list SSRF cho fetch avatar |
| `media.avatar.max-bytes` | 5242880 (5 MB) | |
| `MINIO_ENDPOINT` / `MINIO_ACCESS_KEY` / `MINIO_SECRET_KEY` / `MINIO_BUCKET` | localhost:9000 / minioadmin / … / `video-media` | Credential MinIO **chỉ nằm ở service này** — đó là lý do cleanup chạy ở đây chứ không ở video-service |

## 6. Bố cục object trong bucket (`MediaKeys`)

| Loại | Key | Ai ghi | Ai xoá |
|---|---|---|---|
| Raw upload | `raw/{userId}/{videoId}.mp4` | client (presigned POST từ video-service) | cleanup, hoặc lifecycle 7 ngày |
| Playback | `hls/{videoId}/source.mp4` | media-worker transcode | cleanup |
| (HLS thật, tương lai) | `hls/{videoId}/master.m3u8` + segment | chưa làm | cleanup (list prefix) |
| Thumbnail | `thumbnails/{videoId}.jpg` | media-worker transcode | cleanup |
| Hover preview | `previews/{videoId}.webp` | media-worker transcode | cleanup |
| Avatar | `avatars/{userId}.jpg` | media-worker mirror **hoặc** user-service `POST /me/avatar` | (không xoá; ghi đè) |

## 7. kafka-lib

`media-worker` **có** dùng `kafka-lib` error handler: consumer lỗi → retry 3 lần → `<topic>.DLT`. Chưa có outbox (không cần — không ghi DB, hai producer tự chờ ack).
