# Ranking model — huấn luyện và vận hành `rank-service`

Tài liệu này dành cho người **vận hành và huấn luyện** mô hình xếp hạng feed, không phải cho client mobile. Client chỉ cần `docs/recommendation-service-api.md` — nó không nhìn thấy `rank-service` và không cần biết mô hình có tồn tại hay không.

## 1. Mô hình nằm ở đâu trong luồng

```
video-service ──VideoPublishedEvent──┐
                                     ├─► analytics-service ──► ClickHouse (watch_events, video_tags)
interaction-service ──VideoWatchEvent┘                              │
                                                                    │ train.py (offline, thủ công)
                                                                    ▼
                                                            model/model.txt (LightGBM)
                                                                    │
recommendation-service ──POST /rank (5 feature/video)──► rank-service :8098
        │                                                       (mạng nội bộ, không JWT)
        └─► GET /api/v1/recommendations/feed
```

**Chỉ thứ tự là do mô hình quyết định.** Việc *video nào được đưa vào danh sách xét* vẫn do luật cũ làm: trending + tag của người xem, trong `FeedServiceImpl`. Tách như vậy có ba lý do:

1. Mô hình sai thì feed xấu đi, không rỗng.
2. Điểm trending là engagement đã suy giảm theo giờ — nó **không tồn tại trong lịch sử event**, nên không thể tái dựng lúc huấn luyện. Cho mô hình học dựa vào nó là dạy nó dựa vào một con số mà lúc phục vụ nó sẽ nhận một con số khác.
3. Sinh candidate rẻ và ổn định. Xếp hạng mới là chỗ mô hình có giá trị.

## 2. Feature — hợp đồng giữa huấn luyện và phục vụ

Năm feature, tên trên dây và tên cột ClickHouse **cố ý giống hệt nhau**:

| Feature | Nghĩa | Lúc phục vụ lấy từ | Lúc huấn luyện dựng từ |
|---|---|---|---|
| `log_watches` | `ln(1 + số lượt xem)` | ZSET `reco:video:watches` | `count()` trên `watch_events` trước mốc cắt |
| `completion_rate` | tỉ lệ xem hết, `0.5` nếu dưới 5 lượt | ZSET `reco:video:completions` | `countIf(completed)` / `count()` |
| `age_hours` | số giờ kể từ lúc đăng | ZSET `reco:video:published` | `video_tags.published_at` |
| `tag_affinity` | tổng độ thích của người xem trên các tag của video | ZSET `reco:user:tags:{id}` | dựng lại từ `watch_events` × `video_tags` |
| `tag_overlap` | số tag khớp | đếm khi ghép tag→video | như trên |

Đây là chỗ dễ hỏng nhất trong toàn hệ thống và **hỏng hoàn toàn im lặng**: đổi tên hoặc đổi thứ tự một cột ở một phía thì mô hình vẫn trả về số thực trông rất tự tin, API vẫn 200, log vẫn sạch, chỉ có feed là tệ hơn cả heuristic nó vừa thay thế. Ba lớp chặn:

- `features.py` giữ **một** định nghĩa `FEATURE_NAMES`; cả `app.py` lẫn `train.py` import từ đó, không ai chép lại.
- `app.py` lúc load model so `model.feature_name()` với `FEATURE_NAMES`, lệch thì **từ chối khởi động** thay vì phục vụ.
- Hai test đối xứng: `RankClientTest.candidateFeatures_serializeUnderTheColumnNamesTheModelWasTrainedOn` (Java) và `test_request_schema_carries_exactly_the_model_features` (Python).

Luật `completion_rate` (ngưỡng 5 lượt) và luật skip (`< 20%` → `-0.5`) cũng nằm trong `features.py`, đúng bằng hằng số trong `FeedServiceImpl` và `RecommendationServiceImpl`. Sửa một bên mà quên bên kia thì cột `tag_affinity` mang hai nghĩa khác nhau ở hai phía.

## 3. Mô hình học cái gì — và không học cái gì

Nhãn là `completed`: **cho rằng người ta đã bấm vào xem, họ có xem hết không.**

Đó **không** phải câu hỏi "họ có bấm vào không". `watch_events` chỉ chứa video người ta thật sự đã xem, nên mọi dòng huấn luyện đều đã sống sót qua một quyết định mà mô hình không bao giờ nhìn thấy. Đây là selection bias thật, không phải chi tiết lý thuyết — nó có nghĩa là mô hình xếp hạng tốt *trong* tập candidate, và không nói được gì về việc tập candidate có đúng không.

Muốn sửa phải **log impression**: ghi lại những video đã được phục vụ nhưng không được xem, để có negative thật. Hiện chưa có gì sinh ra dữ liệu đó. Đó là việc của chặng sau, không phải thứ có thể vá trong `train.py`.

**Chống rò rỉ nhãn**: feature dựng tại mốc cắt `T`, nhãn lấy trong `[T, T+1 ngày)`. Không dòng nào được chấm bằng một lượt xem chưa xảy ra. Đánh giá dùng mốc cắt **muộn hơn** mốc huấn luyện — chia theo thời gian, không chia ngẫu nhiên, vì câu hỏi thật là "mô hình có chạy được ngày mai không".

## 4. Chạy

```bash
make infra-up          # cần ClickHouse
make rank-up           # dựng và chạy rank-service (không publish port ra ngoài)
make rank-test         # 8 test Python, chạy trong image vì LightGBM cần libgomp
make rank-train        # huấn luyện từ ClickHouse rồi POST /reload
```

Chưa có dữ liệu xem thật thì `train.py` sẽ dừng và nói ra. Muốn demo hoặc smoke test toàn luồng:

```bash
docker compose run --rm rank-service python seed_demo_data.py
```

`seed_demo_data.py` sinh watch log tổng hợp, trong đó mỗi người thích 2 tag và xem hết video có tag đó nhiều hơn hẳn. Sở thích này **cố ý không phải là một feature** — nếu mô hình không tìm ra nó qua `tag_affinity` thì pipeline đang hỏng. Trên dữ liệu sinh gần đây nhất: AUC 0.7377, nDCG@10 0.3739, và `tag_affinity` là feature quan trọng nhất với khoảng cách lớn — đúng như mong đợi.

⚠️ Script này ghi thẳng vào bảng mà `analytics-service` đang ghi. Chỉ chạy trên database vứt đi.

`train.py` ghi `services/rank-service/model/model.txt`, thư mục này được bind mount vào container và **không nằm trong git**. Không có file mô hình thì service vẫn chạy, `/health` báo `model_version: "none"`, `/rank` trả về không điểm nào, và recommendation-service tự xếp hạng bằng heuristic.

## 5. Vì sao `rank-service` không có auth và không nằm sau gateway

Nó không giữ dữ liệu người dùng, không lưu gì, và không nhận id nào ngoài `user_id` để log. Nó chỉ đến được qua tên service trên mạng compose. Đưa nó ra gateway là thêm một bề mặt tấn công để đổi lấy đúng con số không.

Timeout gọi sang là **150ms** cho cả connect lẫn read, cấu hình ở `reco.rank.timeout-millis`. Nó nằm trong một request có người đang chờ, nên một ranker treo phải tốn của feed một phần giây rồi bị bỏ qua — không phải giữ thread cho đến khi read timeout mặc định (tính bằng phút) hết hạn. Mọi đường lỗi đều trả về "không có điểm", không ném exception.

## 6. Việc còn thiếu

- **Impression logging** — xem mục 3. Đây là hạn chế lớn nhất.
- **Không có A/B test**: chưa có cách đo mô hình có thật sự tốt hơn heuristic trên người dùng thật. AUC offline không trả lời câu đó.
- **Huấn luyện thủ công**: không có lịch, không có retrain tự động, không có cảnh báo khi chất lượng tụt.
- **Mô hình lưu ở bind mount, không phải MinIO**: nhiều replica `rank-service` sẽ không thấy chung một file. Đủ dùng cho một node; thêm replica thì phải chuyển sang object storage.
