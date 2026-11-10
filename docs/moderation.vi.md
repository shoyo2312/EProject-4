# Kiểm duyệt video tự động

> Bản dịch tiếng Việt của [`moderation.md`](moderation.md). Bản tiếng Anh là bản gốc; khi hai bản
> lệch nhau thì tin bản tiếng Anh.

Mọi video upload lên đều được quét hình ảnh NSFW, cùng bạo lực, máu me, ma tuý, tai nạn và cờ bạc,
trước khi bất kỳ ai xem được. Bước kiểm chạy sau khi transcode và trước khi publish, nên một video
không qua được thì chưa từng xuất hiện trên feed lúc nào. Title, description và tag cũng được đọc
lúc publish, và mọi comment cũng vậy trước khi được đăng — xem
[Chữ: caption và comment](#chữ-caption-và-comment).

Không có gì ở đây tốn tiền: cả ba classifier — hai cho frame, một cho chữ — đều là model
open-weight chạy trên CPU trong một container do repo này build, còn luật chữ là một danh sách từ
đọc ngay trong process.

## Luồng xử lý

```
video-service          media-worker                moderation-service        video-service
─────────────          ────────────                ──────────────────        ─────────────
VideoPublishedEvent ─► transcode ─► VideoTranscodedEvent ────────────────────► PENDING_MODERATION
                       │
                       └─ (consumer group thứ hai trên cùng topic)
                          lấy 10 frame ─► POST /moderate ─► verdict
                          APPROVED? thumbnail + preview ─► POST /moderate ─► chỉ được giữ
                                                                │
                          VideoModerationCompletedEvent ◄───────┘
                                                │
                                                └──────────────────────────► PUBLISHED
                                                                             PENDING_REVIEW
                                                                             REJECTED
```

Hai event, không phải một. Transcode và kiểm duyệt hỏng vì những lý do không liên quan nhau — một
classifier đang chết không nói lên gì về việc file có phát được hay không — và gộp verdict vào
`VideoTranscodedEvent` nghĩa là video không thể được ghi nhận là đã transcode cho tới khi model cũng
đã trả lời. Tách riêng còn cho phép chạy lại một sự cố kiểm duyệt mà không phải encode lại gì: hai
consumer group trên `media.video-transcoded-events` dời offset độc lập nhau.

| Topic | Producer | Consumer |
|---|---|---|
| `media.video-transcoded-events` | media-worker | video-service (`video-service`), media-worker (`media-worker-moderation`), search-service (`search-service`), recommendation-service (`recommendation-service`) |
| `media.video-moderation-events` | media-worker | video-service (`video-service`), search-service (`search-service`), recommendation-service (`recommendation-service`), chat-service (`realtime-<uuid>`, `admin-realtime-<uuid>`) |

## Trạng thái

| Status | Ý nghĩa | Hiển thị? |
|---|---|---|
| `PROCESSING` | đã upload, đang transcode | không |
| `FAILED` | transcode bỏ cuộc — trạng thái cuối, không bao giờ được kiểm duyệt (`failureReason`) | không |
| `PENDING_MODERATION` | đã transcode, đang chờ verdict | không |
| `PENDING_REVIEW` | model không chắc, hoặc không chạy được, hoặc caption bị giữ — admin quyết định | không |
| `PUBLISHED` | đã duyệt | **có** |
| `REJECTED` | model gỡ, không có người tham gia | không |
| `TAKEN_DOWN` | admin gỡ | không |

Mọi read path lọc `status == PUBLISHED` thay vì loại trừ một danh sách trạng thái ẩn, nên một status
mới là vô hình với người xem cho tới khi có gì đó cố ý cho nó đi qua.

`REJECTED` vẫn tách khỏi `TAKEN_DOWN` vì hai thứ này đáng được đếm riêng: một là quyết định của model
mà ngưỡng còn đang được tinh chỉnh, cái kia là quyết định của con người và không bị đặt dấu hỏi.

## Luật quyết định

`services/moderation-service/verdict.py`. Mười frame được lấy rải đều trên toàn bộ video — không phải
mười giây đầu, không thì một video chỉ trở nên khiêu dâm ở gần cuối sẽ được quét trên con số không —
và mỗi frame được chấm điểm NSFW.

| Điều kiện | Verdict | Status |
|---|---|---|
| `maxScore < 0.60` | `APPROVED` | `PUBLISHED` |
| `maxScore >= 0.90` **và** ít nhất 2 frame trên 0.60 | `REJECTED` | `REJECTED` |
| mọi trường hợp còn lại | `REVIEW` | `PENDING_REVIEW` |

Điều kiện thứ hai là hai điều kiện có chủ đích. Một classifier chạy trên mười frame của một video
bình thường thỉnh thoảng sẽ đẩy một frame lên trên 0.9 — một cận cảnh màu da, một cú crop xấu, một
frame bắt đúng lúc chuyển cảnh — và reject chỉ vì vậy là gỡ mất video thật. Đòi một frame thứ hai đồng
ý gần như không tốn gì với nội dung khiêu dâm thật, thứ hiếm khi chỉ khiêu dâm ở đúng một trong mười
frame được lấy.

Đây là các con số khởi điểm, chưa phải con số đã chốt. Tinh chỉnh chúng dựa trên
`video.moderation.maxScore` của các upload thật, đó là lý do điểm số được lưu trên document video chứ
không chỉ ghi log. `maxScore` thuộc về `moderation.label`, mà từ harm-v1 thì không phải lúc nào cũng là
`nsfw` — hãy lọc theo nó, không thì ngưỡng NSFW sẽ bị tinh chỉnh dựa trên điểm của CLIP:

```javascript
// video mà model NSFW không chắc, tệ nhất trước
db.videos.find({ status: "PENDING_REVIEW", "moderation.label": "nsfw" }).sort({ "moderation.maxScore": -1 })

// model đã tự tin cỡ nào về những video mà admin sau đó khôi phục?
db.videos.find({ statusBeforeTakedown: "REJECTED" }, { "moderation.maxScore": 1 })
```

## Bạo lực, máu me, ma tuý, tai nạn, cờ bạc

Model NSFW trả lời đúng một câu hỏi, và một trận đánh nhau thì không khiêu dâm: mười upload thử gồm
đánh nhau, dùng ma tuý, máu, một chiếc xe đang cháy và một app cá cược đều được nó chấm dưới 0.12 và đã
được publish. Không có classifier open-weight nào cho các loại này chạy được trên CPU, nên
`harm_classifier.py` hỏi CLIP (`openai/clip-vit-base-patch16`) theo kiểu zero-shot: mỗi frame được so
với một danh sách câu mô tả viết sẵn — năm hạng mục có hại, cộng một danh sách dài hơn các cảnh bình
thường — và tỉ lệ độ khớp rơi vào các câu mô tả của một hạng mục chính là điểm của hạng mục đó.

| Nhãn | Được mô tả là (rút gọn) |
|---|---|
| `violence` | người đánh nhau, đấm, hành hung, súng chĩa vào ai đó, tấn công bằng dao |
| `gore` | vết thương đầy máu, một người dính đầy máu, một xác chết |
| `drugs` | hít cocaine, các đường bột, ống tiêm, tẩu hút crack |
| `accident` | tai nạn xe hơi, xe đang cháy, xác xe đang cháy |
| `gambling` | app cá cược trên điện thoại, tỉ lệ cược, bàn casino, máy đánh bạc |

**Nó chỉ được giữ video.** Không gì nó chấm bị `REJECTED` (`verdict.review_only`). Một phim hành động,
một trận boxing và một con dao làm bếp đều chỉ cách "violence" một câu mô tả, và một model không phân
biệt được chúng thì không được tự mình gỡ video. NSFW vẫn reject theo ngưỡng riêng của nó, và một lần
reject thắng một lần giữ do harm.

**Một frame lạ là không đủ để giữ.** Video sang `PENDING_REVIEW` khi hai frame bằng hoặc vượt
`HARM_REVIEW_THRESHOLD` (0.55), hoặc một frame bằng hoặc vượt `HARM_REVIEW_ALONE_THRESHOLD` (0.80) —
cùng kiểu chốt chặn mà lần reject NSFW đã có từ đầu. Frame được đếm chung trên cả năm nhãn
(`verdict.decide_together`), không đếm trong từng nhãn: CLIP chia một cảnh cho các nhãn kề nhau, và
video ống tiêm bên dưới được chấm gambling ở một frame và drugs ở frame kế tiếp. harm-v1 giữ theo một
frame ở 0.55, và năm trong bảy video bình thường trong hàng chờ của nó bị giữ chỉ vì một frame — xem
[tập gán nhãn thứ hai](#tập-gán-nhãn-thứ-hai). Story ảnh chỉ có một frame, nên chỉ cần đúng frame đó ở
0.55: số frame cần có bị chặn trên bởi số frame thực có. Không có chặn đó thì mọi ảnh dưới 0.80 đều được
duyệt, kể cả ảnh bột (0.71) và ống tiêm (0.71) mà harm-v1 đã giữ.

**Ảnh động không phải một frame.** Ảnh story tới `/moderate` dưới dạng chính file đã upload, và một
GIF, APNG hay WebP động mở ra như một ảnh thì chỉ là frame đầu tiên của nó — nên một frame đầu sạch
cho qua mọi thứ phía sau. Upload chỉ ghim content type, không ghim nội dung byte, nên một GIF gửi dưới
dạng `image/png` cũng là trường hợp này. `app._decode` rải tối đa `ANIMATION_FRAMES` (16) frame trên
toàn ảnh động, từ đầu tới cuối, thu mỗi frame về 512px, và các luật ở trên chấm chúng như frame của một
video. Rải theo chỉ số: một khoảnh khắc chớp nhoáng ngắn hơn khoảng cách giữa hai mẫu vẫn có thể lọt,
giống như giữa các frame được lấy mẫu của video.

**Kích thước frame được kiểm trước khi decode.** Ảnh story bị chặn ở 10 MB nhưng không bị chặn theo
pixel: một PNG đơn sắc 500 KB có thể khai 13000×13000, khoảng 1.3 GB sau khi decode và chuyển đổi,
trong một container đang giữ 2 GB model với `mem_limit: 3g`. `app._decode` từ chối mọi frame vượt
`MAX_IMAGE_PIXELS` (64M; một ảnh điện thoại 50 MP là 50.1M) ngay từ header với 400, media-worker retry
rồi giữ ở REVIEW (story thành HELD). Bước kiểm chạy sau `Image.open` và chạy lại sau mỗi lần seek,
không chỉ ở các frame được lấy mẫu: một frame GIF có thể nới rộng canvas, và Pillow decode mọi frame
mà nó seek qua. Chỉ mở JPEG (gồm cả MPO), PNG/APNG, WebP và GIF: Pillow decode ảnh bên trong một file
ICO ngay trước khi `Image.open` trả về. Ảnh tĩnh dưới mức chặn được chấm ở kích thước gốc; thu nhỏ
chúng sẽ đổi điểm số và cần bump `MODERATION_MODEL_VERSION`.

**Cover được kiểm riêng.** Thumbnail (`thumbnails/{id}.jpg`, frame ở giây 1) và preview khi hover
(`previews/{id}.webp`, 3 giây tính từ giây 1 ở 8 fps) là thứ mọi feed card, lưới profile và kết quả
search hiển thị, mà các frame rải đều của một video dài hơn lại bước qua đúng mấy giây đầu đó: với video
60s thì mẫu đầu tiên nằm ở giây 3. Khi các frame rải đều ra APPROVED, media-worker gửi hai object đã
publish tới `/moderate` trong một lần gọi thứ hai (`_decode` bung WebP động thành tối đa 16 frame). Lần
gọi đó chỉ có thể giữ: bất cứ gì khác APPROVED, kể cả REJECTED, đều đưa video về REVIEW, mang nhãn và
điểm của cover với reason `Thumbnail or preview held for review`. Một cover sạch giữ nguyên verdict
của frame rải đều và điểm của nó. Đây là một lần gọi riêng vì các frame của preview là những bản gần
trùng nhau của cùng một khoảnh khắc: trộn vào các frame rải đều thì chúng tự thoả các chốt chặn "frame
thứ hai đồng ý" (`min_reject_frames`, `HARM_MIN_REVIEW_FRAMES`). Object vắng mặt ở cả key public lẫn
`quarantine/` thì bỏ qua; tải hỏng hoặc gọi hỏng thì lượt thử đó thất bại và kết thúc ở REVIEW như mọi
trường hợp khác. Khi tinh chỉnh ngưỡng dựa trên `moderation.maxScore`, hãy loại các video mang reason
đó: điểm của chúng là của cover, không phải của các frame rải đều.

**Thuốc lá và vape không phải ma tuý.** Chúng có câu mô tả riêng trong danh sách bình thường, nên một
clip hút vape rơi vào đó thay vì trôi sang `drugs`. `test_harm.py` ghim điều này.

**Azure chỉ được hỏi về các lần giữ do NSFW.** Azure không có hạng mục ma tuý hay cờ bạc: hỏi nó về
một video ma tuý đang bị giữ thì nó trả severity 0, và thế là video được publish. Một REVIEW chỉ được
chuyển lên khi `nsfw` là nhãn duy nhất đang giữ video. Violence và gore cũng nằm ngoài, dù Azure có hạng
mục Violence. Với 7 video có hại của [tập gán nhãn thứ hai](#tập-gán-nhãn-thứ-hai), Azure đã xem 3 frame
mà một lần chuyển lên sẽ gửi. Nó chấm sáu trong số đó 0 ở mọi hạng mục, trong đó có chiếc áo đẫm máu,
vụ cướp có súng và trận MMA; chỉ người lái xe chảy máu được 6. Chính sách ảnh của Azure coi vết thương,
thể thao đối kháng, chấn thương do tai nạn và đồ vật bị hư hại là *an toàn*. Độ phân giải không phải
nguyên nhân: gửi ở 1280×720 thay vì 398×224 của pipeline thì chỉ có khẩu súng nhích lên, tới 2. Chuyển
violence và gore lên Azure sẽ publish ba trong bốn video đang bị giữ và reject video thứ tư.

**Các câu mô tả chính là model.** Danh sách bình thường quan trọng ngang danh sách có hại — một softmax
chỉ trên "a car on fire" sẽ cho mọi frame 1.0 ở câu đó. Thêm, xoá hay viết lại bất kỳ dòng nào đều làm
dịch mọi điểm số: bump `HARM_MODERATION_MODEL_VERSION` và chấm một tập đã gán nhãn
(`score_samples.py`) trước khi đưa lên.

Những gì nó không thấy được, do chính cách nó được dựng: một vụ mua bán ma tuý trông chỉ như một người
cầm tiền, một clip kinh dị chỉ tối và đỏ, mọi thứ nằm trong âm thanh.

### Tập gán nhãn thứ nhất

Hai mươi mốt upload trên production, mười frame mỗi video, lấy mẫu đúng như media-worker lấy
(2026-10-19, harm-v1 như đã phát hành):

| | Điểm harm cao nhất | Verdict |
|---|---|---|
| xe đang cháy / áo đẫm máu / app cá cược | 0.99 / 0.99 / 1.00 | REVIEW |
| đánh nhau trong club / cướp có súng | 0.97 / 0.89 | REVIEW |
| các đường bột / tài xế chảy máu | 0.71 / 0.64 | REVIEW |
| khuôn mặt kinh dị dưới ánh đèn đỏ | 0.49 (gore) | APPROVED — không phải gore |
| người đội mũ trùm cầm tiền, tiêu đề "ma tuy" | 0.01 | APPROVED — không có ma tuý trong khung hình |
| hút vape ngoài đường | 0.37 | APPROVED, đúng ý định |
| 11 video bình thường (anime, phong cảnh, bàn làm việc, flycam thành phố) | tối đa 0.44 | APPROVED |

0.55 nằm giữa video bình thường cao nhất và video có hại thấp nhất bị bắt, đó là một khoảng cách mỏng
trên một tập nhỏ không có mẫu âm khó — nấu ăn, thể thao, phim hành động, game. Hãy chờ đợi vài tuần
đầu của upload thật sẽ dịch nó; dòng log `scores=nsfw:…,violence:…,…` mang frame tệ nhất của mọi nhãn,
kể cả những nhãn không thắng.

### Tập gán nhãn thứ hai

Bốn mươi ba upload (2026-10-22): mười bốn video harm-v1 đã giữ và mọi video nó đã publish, mỗi video
được đánh giá bằng cách nhìn các frame của nó. harm-v1 giữ cả bảy video có hại, và giữ cùng với chúng
bảy video bình thường:

| Bình thường, bị harm-v1 giữ | harm-v1 | Bị đọc là | harm-v2 |
|---|---|---|---|
| một bài kéo tạ trên máy T-bar ở gym | violence 0.75, 7 trên 9 frame | "a person punching another person" | 0.33, APPROVED |
| edit anime, một nhân vật làm dấu chữ V | violence 0.74, 1 frame | "pointing a gun at someone" | 0.23, APPROVED |
| edit anime đánh nhau | violence 0.66, 2 frame | "punching", "attacking with a knife" | 0.41, APPROVED |
| mặt chú hề kinh dị dưới ánh đèn đỏ | gore 0.64, 1 frame | "a person covered in blood" | 0.63, 1 frame — APPROVED |
| edit anime trên nền lốc xoáy (upload hai lần) | violence 0.61, 1 frame | "punching" | 0.29, APPROVED |
| một con mèo đi trên bàn phím laptop | gambling 0.58, 1 frame | "online gambling odds" | 0.36, APPROVED |

Một chiếc tàu du lịch quay từ trên cao đã được publish ở gambling 0.54 — CLIP biết tàu du lịch qua các
casino trên đó — và giờ chấm 0.03. Bảy video có hại vẫn đều là REVIEW: áo đẫm máu (gore 1.00, 9 frame),
xe đang cháy (accident 0.99, 10), app cá cược (gambling 1.00, 9), cướp có súng (violence 0.86, 3), tài xế
chảy máu (gore 0.85, 5), một trận MMA trong lồng (violence 0.79, 3), một ống tiêm và ma tuý dưới ánh đèn
tím (gambling 0.71, 3 frame chia giữa gambling và drugs).

Hai nguyên nhân, hai cách sửa:

- **Thiếu câu mô tả bình thường.** Softmax chỉ chọn giữa những câu mô tả nó có: một frame không khớp
  câu bình thường nào sẽ dồn trọng số vào câu có hại nào ít sai nhất. Năm câu suýt trúng đã được thêm —
  một phòng gym, một nhân vật anime, một cảnh hành động anime, một chiếc laptop, một con tàu trên biển —
  mỗi câu chỉ được giữ lại khi bỏ nó đi làm một lần giữ sai quay lại. Ba câu nữa (một cử chỉ tay, một bộ
  hoá trang halloween, đồ trang trí nhiều màu) không đổi gì và bị loại. Câu mô tả rộng là cái bẫy:
  "a photo lit by colorful neon lights" kéo theo luôn video ống tiêm dưới ánh đèn tím.
- **Một frame lẻ quyết định.** Thêm năm câu kia rồi, chú hề vẫn còn một frame ở 0.63. Đòi frame thứ hai
  là xong; video có hại nào cũng có từ ba frame trở lên.

Đã kiểm trên mười frame mà media-worker gửi và trên ba mươi frame mỗi video ở những vị trí mà mười frame
kia không bao giờ dùng: không giữ sai và không bỏ sót ở cả hai, với mọi ngưỡng một-frame từ 0.75 tới 0.90.
Vẫn là một tập nhỏ — không có anime máu me, không có đánh nhau ngoài đường, chỉ một cảnh ma tuý. Một trận
đánh nhau vẽ tay giờ được tính là bình thường có chủ đích, nên anime máu me chỉ còn dựa vào câu mô tả
gore.

## Thất bại không bao giờ là pass

Luật là không lỗi nào publish một video và không lỗi nào gỡ một video:

| Cái gì hỏng | Chuyện gì xảy ra |
|---|---|
| moderation-service không tới được hoặc chậm | 3 lần thử, rồi `REVIEW` kèm lý do |
| không decode được frame nào | `REVIEW` ngay — retry cũng chỉ ra cùng con số không |
| một chuỗi verdict mà bản build này không biết | `REVIEW` |
| nhãn của model không có `nsfw` | mọi frame chấm 1.0 — cấu hình sai thì đẩy cho người xử lý |
| admin gỡ video giữa lúc đang kiểm | lệnh gỡ thắng; verdict được ghi lại làm trạng thái mà lần khôi phục sẽ trả về |
| verdict được đọc trước kết quả transcode (khác topic, vd. một consumer restart khi cả hai đang chờ) | verdict được giữ trên video và áp dụng khi kết quả transcode tới; verdict thứ hai bị bỏ qua |
| media-worker không tới được Kafka | event transcode được giao lại và bước kiểm chạy lại |

Không có đường nào để một video nằm ở `PENDING_MODERATION` mà không có gì đẩy nó đi tiếp. Video nằm ở
đó là bug, và đáng đặt cảnh báo.

## Chạy

```bash
make moderation-up      # build + start (lần build đầu tải weights của cả ba model, image ~3 GB)
make moderation-test    # test các luật quyết định — không cần model
make moderation-score-texts FILE=comments.txt   # precision của text classifier trên các comment đã gán nhãn
```

Có thể bỏ text model khỏi image bằng `--build-arg TEXT_MODERATION_MODEL=`; khi đó `/moderate/text`
trả 503 và các service chỉ quét chữ bằng rules. Harm model cũng vậy với
`--build-arg HARM_MODERATION_MODEL=`; khi đó frame chỉ được quét NSFW. Weights của CLIP là một pickle
(`pytorch_model.bin`, openai chưa bao giờ phát hành safetensors): được ghim vào một commit, kiểm
SHA-256 lúc build, và đọc bằng `torch.load(weights_only=True)`.

Container chỉ publish `127.0.0.1:8099`. Khác với rank-service, vốn không publish gì: media-worker
chạy trên host qua `make run-media` chứ không chạy trong compose, và không map port nào thì mọi upload
sẽ trượt bước kiểm và rơi vào hàng chờ admin.

Đặt `MODERATION_ENABLED=false` cho media-worker để bỏ hẳn bước kiểm và duyệt mọi thứ — dùng khi chạy
local mà không bật container kiểm duyệt. Đó là lựa chọn tường minh của người vận hành; không có gì tự
tắt nó khi service chết.

### API

Chỉ nội bộ: không có route ở gateway, không xác thực.

| Endpoint | |
|---|---|
| `POST /moderate` `{"frames": ["<base64 image>", …]}` | `{verdict, label, maxScore, suspiciousFrames, totalFrames, processingTimeMs, model, modelVersion}` — `label` và `maxScore` thuộc về nhãn đã quyết định, còn `model` là model đã sinh ra `maxScore`. Frame vượt `MODERATION_MAX_FRAMES` bị bỏ, không bị từ chối; frame không decode được là 400; 503 trong lúc model đang nạp |
| `POST /moderate/text` | xem [Classifier](#classifier) |
| `GET /config` | các model, version và ngưỡng đang áp dụng, chỉ đọc, cho console admin — đổi một giá trị là một lần deploy |
| `GET /health` | 503 cho tới khi mọi model đã cấu hình nằm trong bộ nhớ |

## Tinh chỉnh

Ngưỡng nằm trong env của moderation-service, không nằm trong media-worker, nên đổi chúng là restart
một container chứ không phải rebuild một service Java:

| Biến | Mặc định |
|---|---|
| `MODERATION_REVIEW_THRESHOLD` | `0.60` |
| `MODERATION_REJECT_THRESHOLD` | `0.90` |
| `MODERATION_MIN_REJECT_FRAMES` | `2` |
| `HARM_REVIEW_THRESHOLD` | `0.55` — chỉ cho các nhãn harm; không có ngưỡng reject cho harm |
| `HARM_MIN_REVIEW_FRAMES` | `2` — số frame bằng hoặc vượt ngưỡng harm, đếm chung mọi nhãn, để giữ một video |
| `HARM_REVIEW_ALONE_THRESHOLD` | `0.80` — một frame cao cỡ này giữ video mà không cần frame thứ hai |
| `MODERATION_MAX_FRAMES` | `32` — số frame chấm mỗi lần gọi, đếm lại sau khi bung ảnh động; phần còn lại bị bỏ |
| `MODERATION_NSFW_LABEL` | `nsfw` — tên class của model NSFW |
| `MODERATION_MODEL` / `MODERATION_MODEL_VERSION` | `Falconsai/nsfw_image_detection` / `nsfw-v1` |
| `TEXT_MAX_TEXTS` / `TEXT_MAX_WINDOWS` | `16` / `32` — xem [Classifier](#classifier) |

Bump `MODERATION_MODEL_VERSION` mỗi khi weights hoặc ngưỡng thay đổi, và
`HARM_MODERATION_MODEL_VERSION` (`harm-v2`) cho mọi ngưỡng hay câu mô tả harm. Cả hai đi kèm mọi
verdict và được ghi lên document video (`nsfw-v1+harm-v2`), và thiếu chúng thì một quyết định từ vài
tháng trước không thể quy về cấu hình đã đưa ra nó.

## Ý kiến thứ hai: Azure AI Content Safety

Classifier local tự giải quyết được hai đầu — video bình thường chấm khoảng `0.01`, nội dung khiêu dâm
khoảng `0.99`. Dải `0.60`–`0.90` là phần nó thực sự kém, và là phần ngốn cả buổi chiều của người kiểm
duyệt. Dải đó, và chỉ dải đó, được gửi lên Azure — và chỉ khi `nsfw` là nhãn duy nhất đang giữ video
(xem [Bạo lực, máu me, ma tuý, tai nạn, cờ bạc](#bạo-lực-máu-me-ma-tuý-tai-nạn-cờ-bạc)).

```
verdict local REVIEW ──▶ 3 frame điểm local cao nhất ──▶ Azure image:analyze
                                                              │
                        severity ≥ 4 ──▶ REJECTED  ◀──────────┤
                        severity ≤ 0 ──▶ APPROVED  ◀──────────┤
                        severity 2   ──▶ REVIEW    ◀──────────┤
                        không trả lời ──▶ REVIEW   ◀──────────┘
```

Chuyển mọi video lên thay vào đó sẽ tiêu hết quota cả tháng vào những ca chưa từng cần: 5.000 ảnh là
500 video với mười frame mỗi video. Chỉ chuyển những video không chắc, và chỉ những frame khiến chúng
không chắc, thì cùng quota đó trải được trên nhiều upload hơn hẳn.

| Biến | Mặc định | Ghi chú |
|---|---|---|
| `AZURE_CONTENT_SAFETY_ENDPOINT` | *(trống)* | Để trống là tắt toàn bộ tính năng |
| `AZURE_CONTENT_SAFETY_KEY` | *(trống)* | Để trống là tắt toàn bộ tính năng |
| `MODERATION_ESCALATE_FRAMES` | `3` | Số frame mỗi video được chuyển lên, điểm local cao nhất trước |
| `MODERATION_ESCALATE_BUDGET` | `10` | Số giây cho toàn bộ ý kiến thứ hai. Hết giờ là không có verdict — `REVIEW`, ghi là `+azure-slow`. Giữ nó tối đa một phần ba `MODERATION_TIMEOUT_MILLIS` (30s) của media-worker: quá mức đó, media-worker thôi chờ, retry từ đầu và bị tính tiền lại cho chính những frame đó |
| `MODERATION_AZURE_REJECT_SEVERITY` | `4` | Thang của Azure là 0/2/4/6 |
| `MODERATION_AZURE_APPROVE_SEVERITY` | `0` | Mọi giá trị ở giữa hai ngưỡng vẫn được đưa cho người |
| `MODERATION_AZURE_CATEGORIES` | `Sexual,Violence,SelfHarm,Hate` | Cả bốn trả về trong một lần gọi, không tốn thêm quota |
| `MODERATION_ESCALATE_TIMEOUT` | `10` | Số giây mỗi request Azure, bị cắt xuống còn phần budget còn lại |
| `AZURE_CONTENT_SAFETY_API_VERSION` | `2024-09-01` | |

`docker-compose.prod.yml` chỉ truyền endpoint và key: `MODERATION_ESCALATE_FRAMES`,
`MODERATION_ESCALATE_BUDGET` và các biến còn lại giữ mặc định trên prod bất kể `.env` ghi gì.

Tạo tài khoản:

1. Azure portal → tạo resource **AI Content Safety**, pricing tier **F0** (miễn phí).
2. Chép *Endpoint* và *Key 1* vào `.env`.
3. `docker compose up -d --force-recreate moderation-service`.

F0 dừng ở 5.000 ảnh mỗi tháng và trả HTTP 429 thay vì tính tiền, nên nó không thể đẩy hoá đơn lên. Hết
quota được ghi trên video là `modelVersion: nsfw-v1+harm-v2+azure-quota` — một tháng `PENDING_REVIEW`
không rõ lý do phải đọc được từ chính các video, không chỉ từ một log đã cuộn mất. Các mã khác là
`azure-error` (không tới được), `azure-shape` (response không nhận ra), `azure-slow`, `azure-none` và,
khi Azure đã trả lời, `azure-sev<N>`.

**Frame rời khỏi máy.** Tính năng này gửi ảnh tĩnh từ upload của user sang Microsoft. Với bản build
local thì ổn; nếu có ngày nó phục vụ user thật thì việc này phải nằm trong điều khoản quyền riêng tư.

Ở đây thất bại cũng không bao giờ là pass. Hết quota, mất mạng, một dạng response mà bản build này không
nhận ra — cả ba để video đúng chỗ model local đã để: `REVIEW`.

## Chữ: caption và comment

Ba lớp, theo thứ tự này, mỗi lớp có công tắc riêng:

1. **Rules**, chạy trong process (`libs/moderation-lib`): một danh sách từ tiếng Việt cộng các tín hiệu
   spam, được video-service, interaction-service và story-service (caption của story) đọc. Không gọi
   mạng — không có gì để chết. Admin thêm term vào danh sách, hoặc gỡ term ra, từ console mà không cần
   deploy.
2. **Bộ lọc riêng của chủ video**, chỉ cho comment: giữ mọi thứ, giữ theo từ khoá, hoặc miễn spam và
   xúc phạm trên video của chính họ.
3. **Một classifier**, `POST /moderate/text` trong moderation-service: một model phát hiện ngôn từ thù
   ghét tiếng Việt đứng sau rules. Nó có thể giữ, không bao giờ từ chối.

Thiết kế: `docs/superpowers/specs/2026-10-13-text-moderation-design.md` (rules, comment bị giữ) và
`2026-10-14-text-moderation-phase2-design.md` (mọi thứ còn lại ở đây).

### Rules

`moderation-lib/src/main/resources/moderation/blocklist-vi.txt`, mỗi dòng một `term | CATEGORY | severity`.
Severity 3 từ chối đoạn chữ; severity 2 chuyển nó cho người. **Cách viết term quyết định cách nó khớp:**
viết có dấu thì chỉ khớp chữ có đúng dấu đó; viết ASCII không dấu thì khớp chữ đã bỏ hết dấu. Bỏ dấu
mọi thứ là thiết kế hiển nhiên và nó sai với tiếng Việt — `cặc`/`các`, `lồn`/`lớn`, `buồi`/`buổi`,
`đĩ`/`đi` bỏ dấu ra cùng chữ cái, và chữ thứ hai của mỗi cặp là từ dùng hằng ngày. Khớp theo từng âm
tiết trọn vẹn, sau khi đã gỡ chữ trông giống nhau, leetspeak, ký tự lặp dài và chữ đánh vần từng chữ cái (`đ.m`).
Header của file có phần còn lại; đọc nó trước khi thêm term, và chạy `ShippedListRegressionTest` sau đó.

`TextNormalizer.prepare` đọc chữ trông giống nhau trên văn bản **đã tách dấu** rồi mới ghép lại. Thứ tự
ngược lại làm rơi các dấu mà một chữ `о` Cyrillic mang theo dưới dạng rời — nó không có dạng ghép với dấu
mũ — nên `lồn` viết bằng chữ đó bị đọc thành `lon` và lọt qua mọi term có dấu, kể cả severity 3. Ký tự
zero-width và các ký tự định dạng khác (Cf) bị bỏ trong cùng lượt đó; để lại thì chúng chẻ một âm tiết
làm hai. Một thay đổi ở normalizer làm đổi thứ được khớp, nên nó bump `version:` của danh sách giống như
một thay đổi ở danh sách.

Nằm trong code thay vì trong danh sách, mỗi thứ severity 2: link (`SPAM`; trong caption, link tới
YouTube, Facebook, Instagram, TikTok, X và Threads được cho qua — `moderation.text.caption-link-allowlist`),
số di động Việt Nam và địa chỉ e-mail (`PERSONAL_INFO`), thương hiệu cá cược như `fb88` hay `789bet`
(`SCAM`), từ năm @mention trở lên trong một comment hay description (`SPAM`).

| Verdict | Comment | Title, description hoặc tag |
|---|---|---|
| có severity 3 | `422 COMMENT_REJECTED`, không lưu gì, một strike | `422 VIDEO_TITLE_/DESCRIPTION_/TAGS_REJECTED`, không lưu gì |
| chỉ severity 2 | giữ lại cho chủ video | video sang `PENDING_REVIEW` |
| không có gì | được đăng | không ảnh hưởng |

Chữ không bao giờ gỡ video. Những term tệ nhất từ chối *request* — file upload vẫn nằm trong MinIO và
được publish lại khi caption được sửa — còn mọi thứ khác chờ người xử lý.

### Term do admin quản lý

File đi kèm bản phát hành chỉ đổi theo release; một false positive phát hiện vào tối thứ Sáu, hay một
thương hiệu lừa đảo mới, không thể chờ tới release. admin-service giữ `moderation_terms` (V7), console
sửa nó, và mọi service quét chữ đều poll danh sách đang áp dụng:

| Endpoint (ROLE_ADMIN) | |
|---|---|
| `GET /api/v1/admin/moderation/terms?q=&action=&page=&size=` | term đang áp dụng, mới nhất trước |
| `GET /api/v1/admin/moderation/terms/removed?q=&action=&page=&size=` | term đã gỡ, gỡ gần nhất trước, kèm người đã gỡ từng term |
| `GET /api/v1/admin/moderation/terms/summary` | version và kích thước danh sách đi kèm, revision của admin, version mà verdict ghi lại |
| `POST /api/v1/admin/moderation/terms` `{term, category, action, note}` | 201; `action` là HOLD, REJECT hoặc EXEMPT |
| `DELETE /api/v1/admin/moderation/terms/{id}` | soft delete |
| `POST /api/v1/admin/moderation/text-preview` `{text, surface}` | các service sẽ xử lý một đoạn chữ ra sao ngay lúc này, gồm cả classifier |

- **HOLD / REJECT** thêm một term (severity 2 / 3) kèm category. Term mà danh sách đi kèm đã gắn cờ ít
  nhất là nặng bằng thì bị từ chối (`INVALID_MODERATION_TERM`): mục nghiêm hơn thắng trong matcher, nên
  nó trông như có tác dụng mà không đổi gì. Nâng một HOLD có sẵn lên REJECT thì được.
- **EXEMPT** gỡ một term có sẵn ra — cách sửa false positive cho tới khi file được sửa. Nó phải chỉ
  đúng một term mà danh sách đi kèm có; nhớ rằng `nhà cái` và `nha cai` là hai term khác nhau.
- Term được làm sạch và lấy key theo cách matcher đọc chúng (`Blocklist.keyOf`): hai cách viết cùng key
  là một term, và term thứ hai đang áp dụng là `409 MODERATION_TERM_EXISTS`.
- Mỗi lần thêm và xoá đều dời `moderation_terms_revision` trong cùng transaction; row lock khiến các
  thay đổi commit theo đúng thứ tự revision.

`AdminTermsSync` (moderation-lib) gọi `GET /api/v1/admin/internal/blocklist` — internal token, `denyAll`
ở gateway — mỗi `moderation.text.admin-terms.refresh` (60 s) và dựng lại matcher khi revision dời: danh
sách đi kèm trừ EXEMPT, cộng HOLD/REJECT. admin-service không tới được thì giữ danh sách cuối cùng;
trước câu trả lời đầu tiên thì chỉ áp dụng danh sách đi kèm. Verdict ghi
`rules-<version danh sách đi kèm>+admin-<revision>`. Poll thay vì push: mọi instance cần mọi thay đổi,
còn một Kafka consumer group chỉ giao mỗi event cho một instance.

### Classifier

`visolex/phobert-v2-hsd` — PhoBERT-base-v2 fine-tune trên ViHSD (CLEAN / OFFENSIVE / HATE), được
moderation-service phục vụ tại `POST /moderate/text` `{"texts": [...]}` (tối đa 16), mỗi đoạn chữ một
kết quả: `{verdict: ALLOW|HOLD, label, score, scores}`.

- **Được ghim và kiểm.** Commit `221aca4…` (lúc build image lấy đúng commit đó, không bao giờ `main`),
  và SHA-256 của weights được kiểm lúc build. Chỉ tải những file không chứa code.
- **Không có remote code.** Config của repository trỏ tới `models.py` của chính nó, thứ sẽ cần
  `trust_remote_code=True` — tức chạy bất cứ gì repository chứa, ở mọi lần build và start. Model của nó
  là encoder RoBERTa, pooler output, dropout và một lớp linear; phần đó được viết lại trong
  `text_classifier.py`, và weights nạp bằng `safetensors` với `strict=True`, nên một checkpoint khác
  hình dạng sẽ làm build hỏng.
- **Chỉ HATE mới giữ**, ở `TEXT_HATE_HOLD_THRESHOLD` (0.90). OFFENSIVE của ViHSD chủ yếu là chửi thề
  dùng để nhấn mạnh, thứ mà danh sách từ cố ý bỏ qua; nó trả về làm label và được ghi lại như một ghi chú.
- **Cửa sổ ngắn.** Chữ được cắt thành các đoạn 64 token, cách nhau 48 (tối đa 32 đoạn), và chấm theo
  đoạn tệ nhất. Đã đo: một câu thù ghét sau ~200 token review đồ ăn được chấm CLEAN 0.99 khi là một cửa
  sổ 256 token — model học từ các comment dài vài chục token. Cắt thành đoạn 64 token thì cùng đoạn chữ
  đó bị giữ ở 0.96, còn một review sạch 2.000 ký tự vẫn CLEAN 0.997.
- **Fail-open về rules.** Lúc đó rules đã chạy xong; classifier không tới được thì là Phase 1, chứ
  không phải mọi comment bị giữ. Sau một lỗi, phía gọi thôi hỏi trong
  `moderation.text.classifier.cooldown` (30 s), nên một sự cố chỉ tốn một lần timeout (`timeout`, 1 s)
  mỗi cooldown. Ngược với frame có chủ đích: frame không có gì khác quét chúng.
- Những gì nó nói trở thành một flag từ nguồn `MODEL`: `HATE:model:HATE:0.97` ở severity 2 khi
  `moderation.text.classifier.enforce` (env `TEXT_CLASSIFIER_ENFORCE`) bật, severity 1 (một ghi chú,
  không quyết định gì) trong trường hợp khác và mỗi khi nó thấy xúc phạm dưới ngưỡng. Verdict ghi
  `+<TEXT_MODERATION_MODEL_VERSION>` khi nó đã trả lời.

Một mẫu chạy qua image đã build, trước mọi tinh chỉnh (một comment ≈ 40 ms trên CPU):

| Chữ | Verdict |
|---|---|
| `Video hay quá, cảm ơn bạn đã chia sẻ`, `con chó nhà tôi dễ thương quá`, `giết thời gian bằng cách lướt tiktok`, `người miền Bắc nói chuyện dễ thương ghê` | ALLOW, CLEAN ≥ 0.99 |
| `đẹp vãi cả lồn`, `game này khó vãi` | ALLOW, CLEAN ≥ 0.98 |
| `ủa sao ngu vậy trời` | ALLOW, một ghi chú: HATE 0.825 |
| `thằng này ngu như bò, đồ óc lợn`, `mày nên chết đi cho xã hội sạch sẽ`, `bọn bắc kỳ chó má cút về quê đi` | HOLD, HATE ≈ 0.97–0.98 |

Chín câu chứng minh việc nối dây, không chứng minh precision. Trước khi áp dụng trên traffic thật, chạy
`TEXT_CLASSIFIER_ENFORCE=false` một thời gian: các ghi chú rơi vào comment (`mod_reason`) và video
(`textModeration.flags`), danh sách admin hiển thị chúng, và `make moderation-score-texts FILE=…` đo
precision trên một file comment bạn đã gán nhãn (`+ ` là nên giữ, `- ` là không nên). Đổi ngưỡng hoặc
weights → bump `TEXT_MODERATION_MODEL_VERSION`. License: bản fine-tune là MIT; base của nó,
`vinai/phobert-base-v2`, ghi AGPL-3.0 trên model card — ổn cho dự án này, kiểm lại trước khi triển khai
thương mại.

### Caption: nơi hai verdict gặp nhau

```
video-service    quét lúc POST /videos ── rules severity 3 ──▶ 422, không lưu gì
                 │  rồi tới classifier, title + description + tag trong một lần gọi
                 └─ video.textModeration, và VideoPublishedEvent.textVerdict (APPROVED | REVIEW)
media-worker     chép nó sang VideoTranscodedEvent.textVerdict
media-worker     frame APPROVED + caption REVIEW ──▶ REVIEW  ("Caption held for review by text moderation")
                 ──▶ VideoModerationCompletedEvent, như trước
```

Verdict được gộp ở media-worker, không ở `Video.outcomeOf`, vì video-service không phải bên duy nhất
đọc `VideoModerationCompletedEvent`: search-service và recommendation-service tự publish video khi
`APPROVED`. Chỉ giữ ở video-service thì video vẫn tìm thấy được và vẫn nằm trong ứng viên For You. Gộp
trước khi event được tạo ra thì mọi bên đọc — status của video-service, search, recommendation, frame
của chat, quarantine media — đều giữ nó cùng một cách mà không phải sửa gì phía mình. Verdict của
caption không bao giờ biến frame thành `REJECTED`, và frame đã ở `REVIEW` hay `REJECTED` thì giữ nguyên.
`MODERATION_ENABLED=false` trên media-worker không bỏ qua nó: caption đã được chấm ở nơi khác.
`textModeration.enforced` là true khi rules đang áp dụng, hoặc khi classifier tự áp một lần giữ của
nó trong lúc rules chỉ đang đo.

### Comment: giữ lại cho chủ video

Comment bị giữ được lưu với `status = HELD` và không gì khác: không có row `comment_index` (nên không
danh sách nào hiển thị được nó), không counter, không `CommentCreatedEvent` (nên chat không bao giờ đẩy
nó lên panel và không ai được thông báo). Tác giả nhận `201` với `status: HELD`. Chủ video xem
`GET /videos/{id}/held-comments` và duyệt (`POST …/comments/{id}/approve`, thao tác này index, đếm và
announce nó đúng như một comment mới) hoặc xoá nó. Xem `interaction-service-api.md` §3.4b–§3.4c.

`CommentScreening` quyết định theo thứ tự: rules (một REJECT đang áp dụng là kết thúc); comment của
chính chủ video không bao giờ bị giữ; bộ lọc của chủ video — `filterAllComments` (`CREATOR_FILTER:all`)
và `commentKeywords` (`CREATOR_FILTER:term:<keyword>`, khớp giống danh sách từ); rồi tới classifier,
chỉ khi chưa có gì giữ comment. Một flag giữ comment từ severity 2 trở lên: flag của rules khi
`moderation.text.enforce` bật, flag của model và của chủ video thì luôn luôn. Chủ video tắt
`filterSpamOffensive` thì được miễn `PROFANITY`, `INSULT` và `SPAM` trên video của họ — không miễn thù
ghét, nội dung tình dục, đe doạ, lừa đảo hay số điện thoại của ai đó, những thứ bảo vệ người đọc thread,
và không bao giờ miễn một REJECT.

Bộ lọc của chủ video nằm ở user-service (`user_settings`, V15), tới `/policy` của video-service qua
`/users/internal/settings` (cache dạng JSON, `user-settings:comment-policy:{id}`, vài giây) và đi cùng
policy mà interaction-service vốn đã đọc. Khi không biết — user-service chết — chúng là mặc định: giữ
spam và xúc phạm, không gì khác. Xem `docs/user-settings.md`.

Mọi comment có flag đều giữ flag đó (`mod_reason`, `mod_version`), dù bị giữ hay đã đăng: một link mà
chủ video đã miễn, comment thô lỗ của chính chủ video, một ghi chú của classifier.
`GET /api/v1/interactions/admin/videos/{id}/comments` hiển thị `moderationReason`; một comment đã đăng
mà có reason là thứ một bộ lọc đã cho qua.

### Strike và hạn chế bình luận

Một comment bị rules từ chối (422) hoặc bị admin gỡ (`CommentRemovedEvent`, chỉ được đếm bởi lần gọi đã
xoá nó) là một strike cho tác giả. `interaction.comment.strikes.threshold` (3) strike trong `window`
(24h) sẽ hạn chế bình luận trong khoảng thời gian kế tiếp của `restrictions` (1h, 24h, 7d — mức cuối lặp
lại); bậc quay về mức đầu sau `step-window` (30d) không có strike nào, và số strike đếm lại từ đầu ở mỗi
lần hạn chế. Comment bị giữ không phải strike — giữ là một câu hỏi cho chủ video — và bất cứ gì
classifier nói cũng không phải.

Tài khoản bị hạn chế nhận `403 COMMENTING_RESTRICTED` kèm `Retry-After` cho mọi comment, kiểm trước mọi
thứ khác; `GET /videos/{id}/comment-permission` trả `COMMENTING_RESTRICTED` kèm `restrictedUntil`. Like,
xoá và đọc không bị ảnh hưởng. Console đọc và gỡ hạn chế ở
`GET` / `DELETE /api/v1/interactions/admin/users/{userId}/comment-restriction` (gỡ cũng xoá luôn strike
và bậc — dành cho khi một term tệ hạn chế nhầm tài khoản vô tội). Nằm trong Redis, và fail-open giống
rate limit: Redis chết nghĩa là không strike và không hạn chế.

### Comment của tài khoản bị ban

interaction-service ghi `UserBannedEvent` / `UserUnbannedEvent` (vốn đã có trên
`admin.moderation-events`) vào bảng Cassandra `user_bans`, có guard theo thời điểm admin-service quyết
định — giống cách video-service giữ `owner_bans`. Mọi danh sách — thread, reply, hàng chờ giữ của chủ
video — bỏ các comment mà `banned_until` của tác giả nằm trong tương lai, với một lần đọc cho các tác
giả của trang. Counter vẫn tính chúng: một lệnh ban hết hạn (auth-service gỡ ban tạm mà không phát event)
hoặc được gỡ sẽ đưa chúng trở lại mà không phải đếm lại gì, đổi lại là tổng số đếm cả những comment
không ai xem được trong lúc ban còn hiệu lực. Danh sách admin hiển thị mọi thứ.

### Giới hạn upload

Không có. video-service từng giới hạn số URL upload và số lần publish mỗi tài khoản
(`UploadRateLimiter`, đã gỡ theo yêu cầu). Không có nó thì một caption bị từ chối 422 có thể thử lại
nhanh tới mức giới hạn per-IP của gateway cho phép (20/s), nên blocklist caption có thể bị dò. Khôi phục
class đó từ lịch sử git nếu việc này bắt đầu quan trọng. Thứ mà publish có kiểm là file raw có tồn tại
(không thì `UPLOAD_NOT_FOUND`): một key bịa ra mà không bị chặn sẽ tốn của listener thread duy nhất của
media-worker vài giây retry mỗi lần, và ở 20/s thì mọi transcode đứng lại.

### Đo trước khi áp dụng

`moderation.text.enforce=false` (env `TEXT_MODERATION_ENFORCE`, trên video-, interaction- và
story-service) tính, ghi log và đếm mọi verdict của rules mà không hành động theo cái nào: không gì bị
giữ hay từ chối, và video không mang verdict chữ nào sang media-worker.
`moderation.text.classifier.enforce=false` làm điều tương tự cho model. Bộ lọc riêng của chủ video luôn
có hiệu lực. Mọi verdict, áp dụng hay không, đều được đếm:

```
text_moderation_decisions_total{surface="comment|video_title|video_description|video_tags|video_overlay|story_caption|story_overlay",
                                decision="allow|hold|reject", category="SPAM|PROFANITY|…|NONE",
                                enforced="true|false"}
text_classifier_opinions_total{surface, label="clean|offensive|hate", verdict="allow|hold", enforced}
text_classifier_unavailable_total
```

Mỗi comment bị gắn cờ giữ `mod_reason` (mọi flag) và `mod_version`; mỗi video giữ
`textModeration.flags` và `.version`. Bump dòng `version:` của danh sách ở mọi thay đổi, cùng lý do
với `MODERATION_MODEL_VERSION`.

```javascript
// video bị giữ chỉ vì caption — frame thì ổn
db.videos.find({ "textModeration.verdict": "REVIEW", "moderation.verdict": "REVIEW",
                 "moderation.reason": /^Caption held/ })
// những gì classifier ghi chú trên caption trong lúc chỉ đang được đo
db.videos.find({ "textModeration.flags": /model:HATE/ })
```

## Chưa có trong V1

- **Âm thanh và lời nói.** Không có gì nghe video.
- **Phần còn lại của chữ.** Giữ ngầm (tác giả thấy comment bị giữ như đã đăng), đối tượng comment theo
  từng video, cùng một đoạn chữ từ nhiều tài khoản, bio và tên hiển thị, strike từ caption. Classifier
  không phải ý kiến thứ hai cho frame, và Azure Content Safety không phải ý kiến thứ hai cho chữ: tiếng
  Việt không nằm trong các ngôn ngữ nó hỗ trợ.
- **Mobile.** `tiktok_mobile` chưa được cập nhật về các status mới. Nếu nó parse status thành một enum
  Dart thì `PENDING_MODERATION` và `REJECTED` có thể ném lỗi thay vì xuống cấp nhẹ nhàng.
