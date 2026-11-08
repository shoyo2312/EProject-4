#!/usr/bin/env python3
"""TikTok Studio for one real account: upload studio-media/*.mp4 as that account, then give the clips
an audience from the seed personas through the public API, and re-time it all as three months of a
growing channel.

    python3 scripts/seed/studio.py [--owner vominhhung031] [--media ~/Projects/EProject/EProject4/studio-media]
    python3 scripts/seed/studio.py --only backdate      # one phase: prep,upload,plan,birthdates,engage,settings,backdate

Runs after the main seed (it needs its personas and admin login). The account signs in with Google, so
there is no password: tokens are minted with JWT_SECRET from .env. State goes to <media>/studio-ledger.json,
so a re-run resumes.

What Studio reads and where it comes from:
- views, watch time, retention, traffic sources, search queries: /view + /watch per viewer, with `source`.
- viewers, new viewers, followers vs non-followers, age groups: real persona ids; each persona gets a
  birth date (PATCH /users/me) the way the profile prompt would.
- likes, comments (with the owner's replies and hearts), shares, saves, reposts, follows (with the video
  they came from), a few unfollows.
- 200 personas cap the distinct viewers of a video, so each clip also gets anonymous plays (user_id 0)
  written straight to ClickHouse and the view counters, like the main seed's own views. They count as
  views, never as viewers or active users.

Then everything moves onto the timeline: posts on the clips' original upload dates (shifted to end
yesterday), each viewer's session front-loaded after posting at Vietnamese waking hours, the channel
getting bigger over time. Running backdate.py again later re-draws "other" watch rows and scrambles
these viewers: run this script's backdate phase after it.
"""
import argparse
import base64
import hashlib
import hmac
import json
import os
import random
import subprocess
import sys
import threading
import time
import uuid
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import seed  # noqa: E402
from seed import ApiError, Ledger, admin_api, inter, log, post_minio, user_api, video_api  # noqa: E402
from backdate import ch_ts, clickhouse, cql_copy, cql_ts, cqlsh, es_bulk, es_ts, mongo_ts, mongosh, psql, redis  # noqa: E402
from scenario import VN, iso, parse  # noqa: E402

MAIN_LEDGER = Path("~/Projects/EProject/EProject4/seed-media/ledger.json").expanduser()
WAITING = ("PROCESSING", "PENDING_MODERATION")
# Relative share of viewers by hour of the day in Vietnam: lunch and the evening.
HOURS = [3, 2, 1, 1, 1, 1, 2, 4, 5, 5, 5, 6, 8, 7, 5, 5, 5, 6, 8, 10, 11, 12, 10, 6]
POST_HOURS = [0] * 7 + [1, 1, 1, 1, 3, 3, 1, 1, 1, 1, 2, 3, 4, 5, 5, 3, 1]

# ------------------------------------------------------------------------------------------- content

# clip id -> (title, tags, category, search phrases). The owner's own words, in Vietnamese, matching the clip.
# Another media dir brings its own as <media>/clips.json: {"<clip id>": [title, [tags], category, [phrases]]}.
CLIPS = {
    "7671518120997047560": ("Chó nhà ai bơi như ca nô thế này 😂", ["cho", "cun", "haihuoc", "fyp"], "pet", ["chó bơi", "chó bơi như ca nô"]),
    "7673766598586502431": ("Bé mèo cam nghịch nhất quả đất 🧡", ["meocam", "meo", "cute", "fyp"], "cat", ["mèo cam", "mèo cam nghịch"]),
    "7675583558064426248": ("Hiệu ứng gì đây mấy bà ơi 😹", ["meo", "meohaihuoc", "xuhuong"], "cat", ["mèo hài", "hiệu ứng mèo"]),
    "7678202366713220373": ("Đi lạc vào khu vườn cổ tích 🌿", ["chill", "thiennhien", "healing", "travel"], "chill", ["khu vườn cổ tích", "đường hầm cây xanh"]),
    "7680217109233749268": ("Ngày cuối của mùa hè, chỉ muốn đứng yên ở đây thật lâu", ["vibe", "thiennhien", "cinematic", "chill"], "chill", ["rừng chill"]),
    "7680366471045795092": ("Theo đuổi một điều mãi không chạm tới được thì nghĩa là gì nhỉ…", ["tamtrang", "chill", "mua", "healing"], "chill", ["video tâm trạng", "tâm trạng"]),
    "7680441803018882325": ("Cây cầu hoa giấy đẹp nhất mình từng thấy 🌸", ["hoagiay", "xuhuong", "viral", "fyp"], "chill", ["hoa giấy", "cầu hoa giấy"]),
    "7680568163867757844": ("Mèo hoang cũng có cách hạnh phúc của riêng nó 🐈", ["meohoang", "meo", "chualanh"], "cat", ["mèo hoang"]),
    "7680636423678037268": ("Mưa ngoài cửa sổ, một góc làm việc nhỏ là đủ yên", ["mua", "chill", "goclamviec"], "chill", ["mưa chill", "góc làm việc"]),
    "7681385392616131862": ("Thiên đường nghỉ dưỡng giữa rừng ở Bali 🕊️", ["bali", "dulich", "travel", "healing"], "travel", ["bali", "resort bali"]),
    "7682479038371351830": ("Setup này đủ cinematic chưa mọi người?", ["setup", "techtok", "desksetup", "gocmay"], "setup", ["setup bàn làm việc", "setup đỏ"]),
    "7682644792810949895": ("Điệu nhảy Sơn Tây đang hot bên Douyin, cuốn thật sự 💃", ["douyin", "nhay", "trend", "xuhuong"], "dance", ["nhảy douyin", "sơn tây dao"]),
    "7683241768506248480": ("Có những nơi im lặng là cuộc trò chuyện hay nhất 😌", ["chamonix", "trekking", "nuituyet", "travel"], "travel", ["chamonix", "núi tuyết"]),
    "7685523887446035744": ("Hoàng hôn trên đỉnh đồi, vài giây thôi mà nhớ mãi", ["hoanghon", "cinematic", "chill"], "chill", ["hoàng hôn"]),
    "7685620583131860242": ("Phong Miên đang hot ở Đà Lạt nè, đi chưa mọi người?", ["dalat", "phongmien", "dulichdalat", "cinematic"], "travel", ["phong miên đà lạt", "đà lạt", "quán phong miên"]),
    "7685683762612555022": ("Một chú cừu, một cầu vồng và cả bầu trời Faroe 🌈", ["faroe", "cuu", "travel", "thiennhien"], "travel", ["quần đảo faroe", "faroe"]),
    "7686309502794108192": ("Lên đời setup mới, HP Victus chạy mượt phết", ["setup", "laptop", "hpvictus", "techtok"], "setup", ["hp victus", "setup laptop gaming"]),
    "7686969678278741280": ("22 giây đáng nhớ nhất đời mình 🏎️ Porsche GT3 qua đèo Susten", ["porsche", "gt3", "pov", "xe"], "car", ["porsche gt3", "đèo susten"]),
    "7687218990552288545": ("Khung cửa đá tự nhiên nhìn ra cả thung lũng", ["thiennhien", "travel", "canhdep"], "travel", ["cổng đá tự nhiên"]),
    "7687231991359606037": ("Nhìn từ dưới lên thì bé tròn vo thế này đây 😂🐾", ["meo", "meobeo", "cat", "haihuoc"], "cat", ["mèo béo", "mèo tròn"]),
    "7687525299701042462": ("Chiều nay trời đẹp quá, không nói được gì luôn", ["hoanghon", "chill", "bautroi"], "chill", ["bầu trời chiều"]),
    "7687615394378517782": ("Đi xe miễn phí 😂", ["ngua", "meme", "haihuoc", "fyp"], "horse", ["ngựa meme", "ngựa đi máy cắt cỏ"]),
    "7688302241245039880": ("Học bài cùng mình không? 📚", ["hoctap", "studywithme", "xuhuong"], "study", ["study with me"]),
    "7688467046278941974": ("Ngựa nhà mình đi giao máy giặt 😭", ["ngua", "meme", "haihuoc", "viral"], "horse", ["ngựa meme", "ngựa máy giặt"]),
    "7688614979083488533": ("Vòng xoay hoa giấy lúc nửa đêm", ["hoagiay", "dem", "xuhuong"], "chill", ["hoa giấy"]),
    "7689053638160829717": ("Cứ sống vui, sống thật, sống theo cách khiến mình thấy bình yên 🌼", ["hoa", "binhyen", "chill"], "chill", ["bình yên"]),
    "7689128142769900801": ("Thế nào là quyết không cho bóng chạm sàn 🏐", ["bongchuyen", "volleyball", "thethao"], "sport", ["bóng chuyền", "pha cứu bóng"]),
    "7692177861108583713": ("Neymar Jr và những pha xử lý không phải ai cũng làm được", ["neymar", "bongda", "football"], "sport", ["neymar", "neymar skill"]),
    "7692436985079680269": ("Chủ mình thích chơi với mình lắm 😸", ["meoanhlongngan", "meocon", "cute"], "cat", ["mèo anh lông ngắn"]),
    "7692701610669100308": ("Nước trong đến mức nhìn thấy cả đáy hồ 💙", ["cuutraicau", "dulichtrungquoc", "thiennhien"], "travel", ["cửu trại câu"]),
    "7693176850973052212": ("Thành phố sau cơn mưa, đèn vàng và tiếng xe", ["mua", "thanhpho", "dem", "chill"], "chill", ["mưa đêm"]),
    "7693216293540990240": ("Mây ngũ sắc trên cung điện, may lắm mới gặp", ["mayngusac", "canhdep", "travel"], "travel", ["mây ngũ sắc"]),
    "7693240629551254817": ("Thư giãn kiểu dân mê xe 💆‍♂️", ["porsche", "gt3", "pov", "carlover"], "car", ["porsche gt3"]),
    "7693807392353438983": ("Bé mèo ngủ trưa giữa rừng thông, ước gì là mình", ["meo", "chill", "healing"], "cat", ["mèo ngủ"]),
    "7695070519833464096": ("London lúc chạng vạng 🇬🇧", ["london", "travel", "dulich"], "travel", ["london"]),
    "7695468507697368352": ("Bé Nala tí hon trong túi áo 🤎", ["meocon", "meoanhlongngan", "cute"], "cat", ["mèo trong túi áo", "mèo nâu"]),
    "7695782214813961504": ("Căn hộ nhìn ra biển lúc hoàng hôn, ai mà không mơ", ["hoanghon", "bien", "canho", "chill"], "chill", ["căn hộ view biển"]),
    "7695867258345770247": ("Bé mèo nằm ườn trong bếp, đuổi kiểu gì cũng không đi 😹", ["meo", "meohaihuoc", "catsoftiktok"], "cat", ["mèo lười"]),
    "7696336867263827214": ("Săn mây ở độ cao này mới thấy mình bé nhỏ", ["sanmay", "nui", "travel"], "travel", ["săn mây"]),
    "7696577128724303124": ("Đêm mưa, đường vắng, đèn vàng", ["mua", "dem", "tamtrang"], "chill", ["đêm mưa"]),
    "7696888286010232084": ("Mùa thu ở Cửu Trại Câu đẹp như tranh vẽ 🍁", ["muathu", "cuutraicau", "travel"], "travel", ["cửu trại câu mùa thu", "cửu trại câu"]),
    "7698921913745165601": ("Có ai dám chui vào đây không 😳", ["hang", "phieuluu", "kyla"], "travel", ["hang động kỳ lạ"]),
    "7699165516467932446": ("Họ chế ra một ngôi nhà biết bay… và bay qua mặt nước thật 🏡✈️", ["sangtao", "kyla", "viral"], "fun", ["nhà biết bay"]),
    "7699888753619373318": ("Tomb Raider bật DLSS đẹp muốn xỉu 🤩", ["tombraider", "game", "4k"], "game", ["tomb raider"]),
    "7701178914936311048": ("Edit Valorant trên nền Beauty And A Beat", ["valorant", "edit", "game"], "game", ["valorant edit"]),
    "7702036099887140103": ("Mưa ở phố cổ, chỉ muốn ngồi nghe mưa cả ngày", ["mua", "phoco", "chill", "healing"], "chill", ["phố cổ mưa"]),
    "7702059261722922254": ("Khởi đầu và kết thúc", ["bien", "tamtrang", "chill"], "chill", ["biển tâm trạng"]),
    "7702908658820189473": ("Cái cây phát sáng giữa đêm, nhìn như phim vậy ✨", ["dem", "canhdep", "cinematic"], "chill", ["cây phát sáng"]),
}

COMMENTS = {
    "cat": ["Bé dễ thương xỉu 😭", "Ủa sao mà tròn vậy trời 🥹", "Mê bé này quá, cho mình xin vía nuôi mèo với", "Bé tên gì vậy bạn ơi?",
            "Mèo nhà mình cũng y chang luôn 😂", "Nhìn cái mặt bé là hết buồn", "Muốn bế về nuôi quá", "Bé mấy tháng rồi bạn?",
            "Trời ơi cái chân bé 🐾", "Xem đi xem lại mấy lần rồi 😹", "Mèo là chúa tể của ngôi nhà", "Bé lém lỉnh ghê"],
    "pet": ["Bơi nhanh thật sự 😂", "Ủa chó hay ca nô vậy", "Cười xỉu với bé", "Bé giống chó nhà mình quá", "Bé này giống gì vậy bạn?",
            "Nhìn bé vui ghê", "Xem mà cười không nhặt được mồm"],
    "chill": ["Nhìn thôi mà thấy bình yên ghê", "Quay bằng máy gì vậy bạn ơi?", "Nhạc gì vậy ạ, nghe cuốn quá", "Ước gì được ở đây một ngày",
              "Màu phim đẹp quá", "Đây là ở đâu vậy bạn?", "Video của bạn chữa lành ghê", "Xem lúc 2h sáng thấy buồn buồn", "Cho mình xin preset màu với",
              "Thích mấy video kiểu này lắm", "Lưu lại làm hình nền luôn", "Chill thật sự 🌿"],
    "travel": ["Đây là ở đâu vậy bạn?", "Đẹp như tranh luôn", "Note lại để đi liền", "Đi một lần cho biết 😆",
               "Bạn đi tour hay tự túc vậy?", "Chi phí đi tầm bao nhiêu vậy ạ?", "Ước gì có tiền đi chỗ này", "Nhìn mà muốn xách balo lên đi ngay",
               "Cảnh thật hay AI vậy bạn?", "Lưu lại làm động lực kiếm tiền 😂", "Mùa này đi có lạnh không bạn?"],
    "setup": ["Setup xịn quá bạn ơi", "Màn hình bao nhiêu inch vậy?", "Cho xin list đồ với ạ", "Đèn led mua ở đâu vậy bạn?",
              "Góc này làm việc chắc năng suất lắm", "Tổng setup hết bao nhiêu vậy?", "Bàn phím gì vậy bạn ơi?", "Đẹp mà gọn ghê"],
    "car": ["Tiếng pô nghe đã tai thật", "Đèo đẹp quá trời", "Ước mơ cả đời 😭", "Giấc mơ của mọi thằng con trai", "Lái thử một lần cũng mãn nguyện",
            "Xe này giá bao nhiêu vậy bạn?", "POV mà như phim", "Nghe tiếng máy nổi da gà"],
    "horse": ["Cười đau bụng 😂😂", "Ngựa nhà ai mà lầy vậy", "Tới công chuyện luôn", "Ủa alo 😭", "Xem 10 lần vẫn cười",
              "Con ngựa này đi làm shipper hả 😂", "Meme năm nay đây rồi", "Ai làm cái edit này thiên tài"],
    "sport": ["Pha này đỉnh thật", "Phản xạ nhanh ghê", "Xem lại mấy lần vẫn sướng", "Quá đẳng cấp", "Đúng là đẳng cấp thế giới",
              "Trận này xem trực tiếp mà hét banh nhà", "Ai xem trận này chưa?"],
    "game": ["Đồ hoạ đẹp quá", "Máy cấu hình gì vậy bạn?", "Edit cuốn quá", "Nhạc hợp ghê", "Game này chơi có hay không bạn?",
             "Card gì mà chạy mượt vậy", "Ai cùng chơi không"],
    "dance": ["Cuốn quá trời", "Học theo mà chân tay lóng ngóng 😂", "Nhạc nghe nghiện luôn", "Đồng đều ghê", "Có ai nhảy theo được chưa"],
    "study": ["Học cùng bạn nè 📚", "Góc học tập xinh quá", "Xem xong có động lực học liền", "Tai nghe gì vậy bạn?", "Cố lên nhé, cùng nhau qua mùa thi"],
    "fun": ["Sáng tạo thật sự", "Ủa vậy cũng được hả 😳", "Đỉnh nóc kịch trần", "Người ta giỏi ghê", "Xem mà hồi hộp theo"],
    "anime": ["Edit đỉnh quá bạn ơi 🔥", "Nhạc gì vậy ạ?", "Bạn edit bằng app gì vậy?", "Xem đi xem lại cả chục lần", "Ai còn chờ phần mới điểm danh",
              "Nổi da gà luôn", "Transition mượt ghê", "Sync nhạc chuẩn quá", "Arc này hay nhất bộ", "Xem lại vẫn khóc 😭", "Lưu lại xem hoài",
              "Anime này tên gì vậy bạn?", "Bạn edit lâu chưa vậy, đẹp quá", "Cho xin project với ạ", "Nhân vật mình thích nhất luôn",
              "Tatakae! 🔥", "Levi số 1 không ai cãi", "Mikasa ngầu xỉu", "Cảnh này đỉnh của đỉnh", "Đúng chất peak"],
}
GENERIC = ["Hay quá 👍", "Video chất lượng ghê", "Lên xu hướng rồi kìa", "Follow bạn lâu rồi, video nào cũng hay", "😍😍😍", "Xem hoài không chán",
           "Ra video tiếp đi bạn ơi", "Thả tim cho bạn nè ❤️", "Trôi vô đây đúng lúc cần", "Ai xem năm 2026 điểm danh"]
OWNER_REPLIES = {
    "cat": ["Bé cảm ơn nha 🥰", "Bé lười lắm mà được cái dễ thương 😂", "Hihi bé dễ thương thật", "Cảm ơn bạn đã xem nha"],
    "pet": ["Bé bơi suốt ngày luôn á 😂", "Cảm ơn bạn nha"],
    "chill": ["Mình quay bằng điện thoại thôi á", "Cảm ơn bạn đã xem nha 🥰", "Mình cũng mê khung cảnh này lắm", "Nhạc có gắn trong video rồi nè"],
    "travel": ["Đi đi bạn ơi, đáng lắm luôn", "Đi tự túc là được nha", "Cảnh thật 100% đó bạn", "Mùa này đi là đẹp nhất luôn"],
    "setup": ["Mình để list ở video sau nha", "Đồ chủ yếu mua online thôi á", "Cảm ơn bạn nha 😆"],
    "car": ["Giấc mơ của mình luôn đó 😭", "Nghe tiếng máy là mê", "Cảm ơn bạn nha"],
    "horse": ["Mình cũng cười xỉu 😂", "Ngựa nhà người ta đó bạn 🤣", "Haha đúng rồi đó"],
    "sport": ["Pha này đỉnh thật sự", "Chuẩn luôn bạn"], "game": ["Máy mình tầm trung thôi á", "Cảm ơn bạn nha"],
    "dance": ["Mình cũng tập mãi chưa được 😂"], "study": ["Cùng cố lên nha 💪"], "fun": ["Đỉnh thật sự", "Cảm ơn bạn nha"],
    "anime": ["Cảm ơn bạn nha 🥰", "Mình edit bằng CapCut thôi á", "Tên nhạc mình để trong video rồi nè", "Phần mới sắp ra rồi đó",
              "Mình cũng mê arc này lắm", "Bạn thích nhân vật nào nhất?", "Hihi cảm ơn bạn đã xem"],
}
REPLY_BACK = ["Chuẩn luôn", "+1 nè", "Đúng rồi đó bạn", "Mình cũng thấy vậy 😂", "Công nhận", "Hóng câu trả lời"]

TIERS = {  # distinct viewers, extra plays per viewer, anonymous plays per logged play, then per-viewer odds
    "viral": dict(viewers=(165, 195), extra=1.3, anon=(1.8, 2.6), like=.72, comment=.2, save=.3, share=.2, repost=.07, follow=.14, tail=6),
    "good": dict(viewers=(95, 150), extra=0.9, anon=(1.4, 2.0), like=.55, comment=.14, save=.2, share=.12, repost=.04, follow=.07, tail=3),
    "normal": dict(viewers=(30, 85), extra=0.6, anon=(0.8, 1.4), like=.35, comment=.08, save=.1, share=.05, repost=.015, follow=.025, tail=2),
    "flop": dict(viewers=(6, 18), extra=0.3, anon=(0.3, 0.8), like=.2, comment=.04, save=.04, share=0, repost=0, follow=.01, tail=1.5),
}
SPAN_DAYS = 150  # posting history longer than this is squeezed into it
COMPLETE = {"viral": .62, "good": .55, "normal": .45, "flop": .3}
AGES = [(13, 17, .08), (18, 24, .46), (25, 34, .30), (35, 44, .11), (45, 58, .05)]


# ------------------------------------------------------------------------------------------- state

class State:
    def __init__(self, path):
        self.path, self.lock = path, threading.Lock()
        self.d = json.loads(path.read_text()) if path.exists() else {}
        for k in ("videos", "results", "done"):
            self.d.setdefault(k, {})

    def save(self):
        with self.lock:
            tmp = self.path.with_suffix(".tmp")
            tmp.write_text(json.dumps(self.d, ensure_ascii=False))
            tmp.replace(self.path)


def _b64(b):
    return base64.urlsafe_b64encode(b).rstrip(b"=").decode()


class Tokens:
    """Access tokens minted with the dev JWT_SECRET: a Google sign-in account has no password, and
    logging the personas in again would replace their phone sessions."""

    def __init__(self):
        self.secret = seed._env("JWT_SECRET")
        self.cache, self.lock = {}, threading.Lock()

    def __call__(self, uid):
        with self.lock:
            tok, at = self.cache.get(uid, (None, 0))
            if time.time() - at > 3600:
                now = time.time()
                body = {"sub": str(uid), "role": "USER", "jti": str(uuid.uuid4()), "tokenType": "access",
                        "iat": int(now), "iatMs": int(now * 1000), "exp": int(now + 3 * 3600)}
                msg = _b64(json.dumps({"alg": "HS256", "typ": "JWT"}).encode()) + "." + _b64(json.dumps(body).encode())
                tok = msg + "." + _b64(hmac.new(self.secret.encode(), msg.encode(), hashlib.sha256).digest())
                self.cache[uid] = (tok, now)
            return tok


# ------------------------------------------------------------------------------------------- prep + upload

def prep(media, st):
    """HEVC clips hang media-worker's normalize; hand it H.264 instead (same picture, longest side ≤ 1920)."""
    out = media / "h264"
    out.mkdir(exist_ok=True)
    for f in sorted(media.glob("*.mp4")):
        dst = out / f.name
        if not dst.exists():
            part = out / (f.stem + ".part.mp4")
            scale = "scale=w='if(gte(iw,ih),min(iw,1920),-2)':h='if(gte(iw,ih),-2,min(ih,1920))'"
            r = subprocess.run(["ffmpeg", "-v", "fatal", "-y", "-i", str(f), "-vf", scale, "-c:v", "libx264", "-preset", "veryfast",
                                "-crf", "21", "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "128k", "-shortest",
                                "-movflags", "+faststart", str(part)], check=False)
            if r.returncode != 0:  # a broken download (most packets undecodable): leave it out
                part.unlink(missing_ok=True)
                st.d["videos"].pop(f.stem, None)
                log(f"prep: {f.name} does not decode, skipped")
                continue
            part.replace(dst)
        p = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", str(dst)],
                           capture_output=True, text=True, check=True)
        info = json.loads(f.with_suffix(".info.json").read_text())
        st.d["videos"].setdefault(f.stem, {}).update(dur_ms=int(float(p.stdout) * 1000), uploaded_on=info["upload_date"],
                                                      popularity=info.get("view_count") or 0)
    st.save()
    log(f"prep: {len(st.d['videos'])} clips as H.264")


def upload(media, st, owner, tok):
    for clip, v in sorted(st.d["videos"].items()):
        if v.get("id"):
            continue
        title, tags, _, _ = CLIPS[clip]
        u = video_api.call("POST", "/api/v1/videos/upload-url", {"contentType": "video/mp4"}, token=tok(owner))
        post_minio(u["uploadUrl"], u["formFields"], (media / "h264" / f"{clip}.mp4").read_bytes(), "video/mp4", filename=f"{clip}.mp4")
        desc = title + " " + " ".join("#" + t for t in tags)
        r = video_api.call("POST", "/api/v1/videos", {"title": title, "description": desc, "rawFileUrl": u["fileUrl"],
                                                       "visibility": "PUBLIC", "tags": tags}, token=tok(owner))
        v.update(id=r["id"], status=r["status"])
        st.save()
        log(f"upload {clip} -> {r['id']}")
    deadline = time.time() + 40 * 60
    while True:
        seen, page = {}, 0
        while True:
            r = video_api.call("GET", f"/api/v1/videos/users/{owner}?page={page}&size=50", token=tok(owner))
            seen.update({x["id"]: x["status"] for x in r["content"]})
            page += 1
            if page >= r["page"]["totalPages"]:
                break
        for v in st.d["videos"].values():
            v["status"] = seen.get(v["id"], v["status"])
        st.save()
        c = Counter(v["status"] for v in st.d["videos"].values())
        if not any(s in WAITING for s in c) or time.time() > deadline:
            break
        log(f"pipeline: {dict(c)}")
        time.sleep(20)
    log(f"pipeline done: {dict(c)}")
    held = [v for v in st.d["videos"].values() if v["status"] in ("PENDING_REVIEW", "REJECTED")]
    if held:
        # Stock clips the classifier held by mistake: put them back, and keep the plumbing out of the audit
        # log (as seed.early_restore does; the log does not filter deleted_at).
        ledger = Ledger(MAIN_LEDGER)
        seed.admin_login(ledger)
        for v in held:
            try:
                r = admin_api.call("POST", f"/api/v1/admin/videos/{v['id']}/restore", {"reason": "Classifier false positive"},
                                   token=seed.admin_token(ledger))
                seed.sql("tt-postgres-admin", "admin_db", f"DELETE FROM moderation_actions WHERE id={r['id']} AND action_type='RESTORE_VIDEO';")
                v["status"] = "PUBLISHED"
            except ApiError as e:
                log(f"restore {v['id']} ({v['status']}) failed: {e}")
        st.save()
        log(f"restored {sum(v['status'] == 'PUBLISHED' for v in held)}/{len(held)} held clips")


# ------------------------------------------------------------------------------------------- plan

def snap(rnd, t, floor, end):
    """`t` moved to an hour people watch at, same day; never before `floor` or after `end`."""
    local = t.astimezone(VN)
    h = rnd.choices(range(24), HOURS)[0]
    new = local.replace(hour=h, minute=rnd.randint(0, 59), second=rnd.randint(0, 59), microsecond=rnd.randint(0, 999) * 1000)
    if new <= floor:
        new += timedelta(days=1)
    return new.astimezone(timezone.utc) if floor < new < end else t


def session_start(rnd, post, end, tail_days, fan):
    """Front-loaded: most sessions in the first two days (followers sooner), a tail of weeks on the better videos."""
    u = rnd.random()
    if fan or u < 0.55:
        hours = rnd.expovariate(1 / (5 if fan else 9))
    elif u < 0.85:
        hours = 24 + rnd.expovariate(1 / 36)
    else:
        hours = 72 + rnd.expovariate(1 / (24 * tail_days))
    t = post + timedelta(hours=hours, seconds=rnd.uniform(30, 600))
    if t >= end:
        t = post + (end - post) * rnd.uniform(0.05, 0.95)
    return snap(rnd, t, post + timedelta(minutes=2), end)


def plan(st, owner, pool, joined):
    """Every action with its time, decided up front so the API run and the backdate agree."""
    rnd = random.Random(2026)
    end = parse(st.d["end"])
    clips = sorted((c for c, v in st.d["videos"].items() if v.get("status") == "PUBLISHED"), key=lambda c: st.d["videos"][c]["uploaded_on"])
    # The clips' original upload dates, shifted so the newest went up the day before the seed.
    last = datetime.strptime(st.d["videos"][clips[-1]]["uploaded_on"], "%Y%m%d").date()
    shift_days = (end.astimezone(VN).date() - timedelta(days=1)) - last
    first = datetime.strptime(st.d["videos"][clips[0]]["uploaded_on"], "%Y%m%d").date()
    squeeze = min(1.0, SPAN_DAYS / max(1, (last - first).days))  # a channel older than the platform keeps its order and rhythm, not its years
    posts = {}
    for c in clips:
        d0 = datetime.strptime(st.d["videos"][c]["uploaded_on"], "%Y%m%d").date()
        d = last - timedelta(days=round((last - d0).days * squeeze)) + shift_days
        local = datetime(d.year, d.month, d.day, rnd.choices(range(24), POST_HOURS)[0], rnd.randint(0, 59), rnd.randint(0, 59), tzinfo=VN)
        posts[c] = min(local.astimezone(timezone.utc), end - timedelta(hours=20))
    # Tier: half how big the clip was on TikTok, half how far along the channel is, plus luck.
    pop = sorted(clips, key=lambda c: st.d["videos"][c]["popularity"])
    score = {c: 0.45 * pop.index(c) / len(clips) + 0.45 * i / len(clips) + rnd.uniform(0, 0.3) for i, c in enumerate(clips)}
    ranked = sorted(clips, key=score.get, reverse=True)
    tier = {c: "viral" if i < 4 else "good" if i < 16 else "flop" if i >= len(ranked) - 5 else "normal" for i, c in enumerate(ranked)}

    created = min(posts.values()) - timedelta(days=17, hours=5)
    follows = []  # {follower, following, at, source?, unfollow?}
    for u in rnd.sample([u for u in pool if joined[u] < created], 38):  # whom the owner follows, over the first weeks
        follows.append({"follower": owner, "following": u, "at": iso(snap(rnd, created + timedelta(days=rnd.uniform(0.1, 30)), created, end))})
    follow_at = {}  # persona -> when they followed the owner
    videos = {}
    for c in clips:
        v, t, post = st.d["videos"][c], TIERS[tier[c]], posts[c]
        cat, queries, dur = CLIPS[c][2], CLIPS[c][3], v["dur_ms"]
        eligible = [u for u in pool if joined[u] < post]
        fans = {u for u in eligible if follow_at.get(u, end) < post}
        n = min(len(eligible), rnd.randint(*t["viewers"]))
        viewers = [u for u in sorted(fans) if rnd.random() < (0.9 if tier[c] in ("viral", "good") else 0.6)][:n]
        viewers += rnd.sample([u for u in eligible if u not in viewers], n - len(viewers))
        sessions, acts, comments = [], [], []
        for u in viewers:
            fan = u in fans
            s = session_start(rnd, post, end, t["tail"], fan)
            if fan:
                src = rnd.choices(["FOLLOWING", "FOR_YOU", "PROFILE"], [55, 30, 15])[0]
            else:
                src = rnd.choices(["FOR_YOU", "SEARCH", "PROFILE", "EXPLORE", "MESSAGE", "FRIENDS", "DIRECT"], [76, 8, 5, 4, 4, 2, 1])[0]
            q = rnd.choices(queries, [3] + [1] * (len(queries) - 1))[0] if src == "SEARCH" else None
            loops = 1 + min(6, int(rnd.expovariate(1 / (t["extra"] if dur <= 12000 else t["extra"] * 0.5)) + 0.3))
            at = s
            for i in range(loops):
                done = rnd.random() < COMPLETE[tier[c]] * (1.1 if dur <= 10000 else 0.85 if dur > 30000 else 1)
                if i == loops - 1 and loops > 2 and rnd.random() < 0.15:  # came back days later
                    at = min(at + timedelta(days=rnd.uniform(1, 6)), end - timedelta(minutes=1))
                watched = dur if done else int(dur * (rnd.uniform(0.02, 0.2) if rnd.random() < 0.2 else rnd.betavariate(1.4, 1.8)))
                sessions.append({"u": u, "at": iso(at), "ms": max(300, watched), "src": src, "q": q})
                at += timedelta(milliseconds=max(watched, 1500) + rnd.randint(200, 3000))

            def later():
                return iso(min(s + timedelta(seconds=rnd.uniform(4, 120)), end - timedelta(seconds=1)))
            for kind in ("like", "save", "share", "repost"):
                if rnd.random() < t[kind] * (1.15 if fan else 1):
                    acts.append({"kind": kind, "u": u, "at": later()})
            if rnd.random() < t["comment"]:
                comments.append({"key": f"{c}:{len(comments)}", "u": u, "at": later(), "text": rnd.choice(COMMENTS.get(cat, []) * 3 + GENERIC)})
            if not fan and u not in follow_at and rnd.random() < t["follow"]:
                follow_at[u] = parse(later())
                f = {"follower": u, "following": owner, "at": iso(follow_at[u]), "source": v["id"]}
                if rnd.random() < 0.05:
                    f["unfollow"] = iso(min(follow_at[u] + timedelta(days=rnd.uniform(2, 25)), end - timedelta(minutes=5)))
                follows.append(f)
        # The owner answers about half the comments within hours; viewers answer some too.
        for cm in list(comments):
            at = parse(cm["at"])
            if rnd.random() < 0.45:
                r_at = min(snap(rnd, at + timedelta(hours=rnd.expovariate(1 / 5)), at, end), end - timedelta(seconds=30))
                comments.append({"key": f"{c}:{len(comments)}", "u": owner, "at": iso(r_at), "parent": cm["key"],
                                 "text": rnd.choice(OWNER_REPLIES.get(cat, OWNER_REPLIES["chill"]))})
            if rnd.random() < 0.12:
                r_at = min(at + timedelta(hours=rnd.expovariate(1 / 10)), end - timedelta(seconds=40))
                comments.append({"key": f"{c}:{len(comments)}", "u": rnd.choice(viewers), "at": iso(r_at), "parent": cm["key"],
                                 "text": rnd.choice(REPLY_BACK)})
        comments.sort(key=lambda x: x["at"])
        clikes = []  # the owner hearts most top-level comments; viewers like a few, a long tail on some
        for cm in comments:
            if cm["u"] == owner:
                continue
            at = parse(cm["at"])
            if "parent" not in cm and rnd.random() < 0.6:
                clikes.append({"key": cm["key"], "u": owner, "at": iso(min(at + timedelta(hours=rnd.expovariate(1 / 4)), end - timedelta(seconds=20)))})
            others = [x for x in viewers if x != cm["u"]]
            n_likes = max(0, min(len(others), int(rnd.paretovariate(1.2)) - 1 + (3 if tier[c] == "viral" else 0)))
            for u in rnd.sample(others, n_likes):
                clikes.append({"key": cm["key"], "u": u, "at": iso(min(at + timedelta(hours=rnd.expovariate(1 / 12)), end - timedelta(seconds=10)))})
        anon = []
        for _ in range(int(len(sessions) * rnd.uniform(*t["anon"]))):
            done = rnd.random() < COMPLETE[tier[c]]
            anon.append({"at": iso(session_start(rnd, post, end, t["tail"], False)),
                         "ms": dur if done else int(dur * rnd.betavariate(1.4, 1.8)) + 300,
                         "src": rnd.choices(["FOR_YOU", "DIRECT", "EXPLORE", "OTHER"], [84, 7, 5, 4])[0]})
        videos[c] = {"id": v["id"], "post": iso(post), "tier": tier[c], "dur": dur, "sessions": sessions, "acts": acts,
                     "comments": comments, "comment_likes": clikes, "anon": anon}
    st.d["plan"] = {"created": iso(created), "videos": videos, "follows": follows}
    st.save()
    tot = Counter()
    for p in videos.values():
        tot["views"] += len(p["sessions"]) + len(p["anon"])
        tot["viewer_sessions"] += len({s["u"] for s in p["sessions"]})
        tot.update(a["kind"] for a in p["acts"])
        tot["comments"] += len(p["comments"])
    tot["followers"] = sum(1 for f in follows if f["following"] == owner and "unfollow" not in f)
    log(f"plan: {len(clips)} videos {dict(Counter(tier.values()))}, {dict(tot)}")


# ------------------------------------------------------------------------------------------- run

class Pacer:
    """Per-author comment pacing under the service limits (3 s cooldown, 10/min, 100/h)."""

    def __init__(self):
        self.lock, self.sent = threading.Lock(), defaultdict(list)

    def wait(self, actor):
        if os.environ.get("STUDIO_NO_PACE"):  # interaction-service started with COMMENT_*=0
            return
        while True:
            with self.lock:
                now = time.time()
                q = self.sent[actor] = [t for t in self.sent[actor] if now - t < 3600]
                minute = [t for t in q if now - t < 60]
                if len(q) >= 95:
                    delay = 3600 - (now - q[0]) + 1
                elif len(minute) >= 9:
                    delay = 60 - (now - minute[0]) + 0.2
                else:
                    delay = max(0, 3.3 - (now - q[-1])) if q else 0
                if delay <= 0:
                    q.append(now)
                    return
            time.sleep(delay)


def birthdates(pool, tok):
    """Each persona tells the profile prompt their birth date, so Studio has age groups to show."""
    rnd = random.Random(31)
    have = set(psql("tt-postgres-user", "user_db", "SELECT user_id FROM user_profiles WHERE birth_date IS NOT NULL;").split())
    today = datetime.now(VN).date()
    n = 0
    for u in pool:
        if str(u) in have:
            continue
        lo, hi, _ = rnd.choices(AGES, [w for *_, w in AGES])[0]
        born = today - timedelta(days=int(365.25 * rnd.uniform(lo + 0.05, hi + 0.95)))
        try:
            user_api.call("PATCH", "/api/v1/users/me", {"birthDate": born.isoformat()}, token=tok(u))
            n += 1
        except ApiError as e:
            log(f"birth date for {u}: {e}")
    log(f"birthdates: {n} set")


def engage(st, owner, tok):
    p, res = st.d["plan"], st.d["results"]
    stats, lock, pacer = Counter(), threading.Lock(), Pacer()

    def call(kind, api, method, path, u, body=None):
        try:
            r = api.call(method, path, body, token=tok(u))
            with lock:
                stats[kind] += 1
            return r if r is not None else {}
        except ApiError as e:
            with lock:
                stats[f"{kind}:{e.code or e.status}"] += 1
            return None

    if not st.d["done"].get("owner_follows"):
        for f in p["follows"]:
            if f["follower"] == owner:
                call("owner_follow", user_api, "POST", f"/api/v1/users/{f['following']}/follow", owner)
        st.d["done"]["owner_follows"] = True
        st.save()

    for c, v in sorted(p["videos"].items(), key=lambda kv: kv[1]["post"]):
        if st.d["done"].get(c):
            continue
        t0 = time.time()
        base = f"/api/v1/interactions/videos/{v['id']}"

        def view(s):
            if call("view", inter, "POST", base + "/view", s["u"], {"playId": uuid.uuid4().hex}) is not None:
                body = {"watchedMs": s["ms"], "durationMs": v["dur"], "source": s["src"], **({"searchQuery": s["q"]} if s["q"] else {})}
                call("watch", inter, "POST", base + "/watch", s["u"], body)

        def act(a):
            r = call(a["kind"], inter, "POST", f"{base}/{a['kind']}", a["u"])
            if a["kind"] == "share" and r:
                with lock:
                    res[f"share:{c}:{a['u']}"] = r.get("shareId")

        with ThreadPoolExecutor(max_workers=16) as ex:
            list(ex.map(view, v["sessions"]))
            list(ex.map(act, v["acts"]))
        for cm in v["comments"]:  # in planned order, so comment ids rise with their times
            if cm["key"] in res:
                continue
            body = {"content": cm["text"]}
            if "parent" in cm:
                if not res.get(cm["parent"]):
                    continue
                body["parentId"] = res[cm["parent"]]
            pacer.wait(cm["u"])
            for attempt in range(8):
                try:
                    res[cm["key"]] = inter.call("POST", base + "/comments", body, token=tok(cm["u"]))["commentId"]
                    stats["comment"] += 1
                    break
                except ApiError as e:
                    if e.code == "COMMENT_RATE_LIMITED" and attempt < 7:
                        time.sleep(20)
                        continue
                    stats[f"comment:{e.code or e.status}"] += 1
                    res[cm["key"]] = None
                    break

        def clike(x):
            if res.get(x["key"]):
                call("comment_like", inter, "POST", f"{base}/comments/{res[x['key']]}/like", x["u"])
        with ThreadPoolExecutor(max_workers=16) as ex:
            list(ex.map(clike, v["comment_likes"]))
        st.d["done"][c] = True
        st.save()
        log(f"{c} {v['tier']}: {len(v['sessions'])} plays, {len(v['acts'])} acts, {len(v['comments'])} comments in {time.time() - t0:.0f}s")

    if not st.d["done"].get("follows"):
        for f in sorted(p["follows"], key=lambda f: f["at"]):
            if f["following"] == owner:
                call("follow", user_api, "POST", f"/api/v1/users/{owner}/follow", f["follower"], {"sourceVideoId": int(f["source"])})
        for f in p["follows"]:
            if f.get("unfollow"):
                call("unfollow", user_api, "DELETE", f"/api/v1/users/{owner}/follow", f["follower"])
        st.d["done"]["follows"] = True
        st.save()
    log(f"engage: {dict(stats)}")


def settings(st, owner, tok):
    """Studio states worth a look: a flop the owner hid, and one video with comments turned off."""
    if st.d["done"].get("settings"):
        return
    vids = st.d["plan"]["videos"]
    flop = next(v for v in vids.values() if v["tier"] == "flop")
    video_api.call("PATCH", f"/api/v1/videos/{flop['id']}/visibility", {"visibility": "PRIVATE"}, token=tok(owner))
    quiet = [v for v in vids.values() if v["tier"] == "normal"][3]
    video_api.call("PATCH", f"/api/v1/videos/{quiet['id']}/comments-setting", {"disabled": True}, token=tok(owner))
    st.d["done"]["settings"] = True
    st.save()
    log(f"settings: {flop['id']} private, {quiet['id']} comments off")


# ------------------------------------------------------------------------------------------- backdate

def json_rows(text):
    out = []
    for line in text.splitlines():
        try:
            row = json.loads(line)
        except ValueError:
            continue
        if isinstance(row, dict):
            out.append(row)
    return out


def chunks(xs, n):
    return [xs[i:i + n] for i in range(0, len(xs), n)]


def shift(s, seconds):
    return iso(parse(s) + timedelta(seconds=seconds))


def backdate(st, owner):
    """Move every row this script caused (written after `started`) to its planned instant."""
    p, res = st.d["plan"], st.d["results"]
    vids = p["videos"]
    by_id = {v["id"]: v for v in vids.values()}
    id_list = ", ".join(f"'{v['id']}'" for v in vids.values())
    w0 = st.d["started"]

    eng = defaultdict(list)  # (TYPE, video, user) -> planned times
    cass = {}  # (kind, video or comment, user) -> time
    for c, v in vids.items():
        eng[("PUBLISHED", v["id"], str(owner))].append(v["post"])
        for a in v["acts"]:
            eng[({"like": "LIKED", "save": "SAVED", "share": "SHARED", "repost": "REPOSTED"}[a["kind"]], v["id"], str(a["u"]))].append(a["at"])
            cass[(a["kind"], v["id"], str(a["u"]))] = a["at"]
        for cm in v["comments"]:
            if res.get(cm["key"]):
                eng[("COMMENTED", v["id"], str(cm["u"]))].append(cm["at"])
        for x in v["comment_likes"]:
            if res.get(x["key"]):
                cass[("comment_like", str(res[x["key"]]), str(x["u"]))] = x["at"]

    # ---- ClickHouse
    rows = [r.split("\t") for r in clickhouse(
        f"SELECT event_id, event_type, video_id, toString(user_id) FROM engagement_events FINAL "
        f"WHERE video_id IN ({id_list}) AND occurred_at > {ch_ts(st.d['end'])} ORDER BY occurred_at FORMAT TSV").splitlines() if r]
    new_eng, seen = {}, Counter()
    for event_id, et, vid, uid in rows:
        times = sorted(eng.get((et, vid, uid), []))
        if times:
            new_eng[event_id] = times[min(seen[(et, vid, uid)], len(times) - 1)]
            seen[(et, vid, uid)] += 1
    for ch in chunks(list(new_eng.items()), 400):
        cases = ", ".join(f"event_id='{e}', {ch_ts(t)}" for e, t in ch)
        clickhouse(f"ALTER TABLE engagement_events UPDATE occurred_at = multiIf({cases}, occurred_at) "
                   f"WHERE event_id IN ({', '.join(repr(e) for e, _ in ch)}) SETTINGS mutations_sync = 1")
    # Planned times all fall before `end`, the sends after it: `> end` is the rows still at their real time.
    watches = json_rows(clickhouse(f"SELECT * FROM watch_events WHERE video_id IN ({id_list}) AND occurred_at > {ch_ts(st.d['end'])} "
                                   f"AND event_id NOT LIKE 'studio-anon-%' ORDER BY occurred_at FORMAT JSONEachRow"))
    planned = defaultdict(list)
    for v in vids.values():
        for s in v["sessions"]:
            planned[(v["id"], str(s["u"]))].append(s["at"])
    used, moved = Counter(), []
    for w in watches:
        k = (w["video_id"], str(w["user_id"]))
        times = sorted(planned.get(k, []))
        if times:
            moved.append(dict(w, occurred_at=times[min(used[k], len(times) - 1)][:23].replace("T", " ")))
            used[k] += 1
    anon = []
    for v in vids.values():
        for i, a in enumerate(v["anon"]):
            anon.append({"event_id": f"studio-anon-{v['id']}-{i}", "video_id": v["id"], "user_id": 0, "watched_ms": min(a["ms"], v["dur"]),
                         "duration_ms": v["dur"], "completed": int(a["ms"] >= v["dur"]), "source": a["src"], "search_query": "",
                         "occurred_at": a["at"][:23].replace("T", " ")})
    # occurred_at leads watch_events' sort key, which ALTER UPDATE cannot touch: insert moved copies, drop the originals.
    clickhouse(f"ALTER TABLE watch_events DELETE WHERE event_id LIKE 'studio-anon-%' AND video_id IN ({id_list}) SETTINGS mutations_sync = 1")
    for ch in chunks(moved + anon, 20000):
        clickhouse("INSERT INTO watch_events FORMAT JSONEachRow", "\n".join(json.dumps(r) for r in ch))
    viewers = ", ".join(sorted({u for _, u in planned}))  # only the personas' plays: the owner's own real ones stay
    clickhouse(f"ALTER TABLE watch_events DELETE WHERE video_id IN ({id_list}) AND occurred_at > {ch_ts(st.d['end'])} "
               f"AND user_id IN ({viewers}) AND event_id NOT LIKE 'studio-anon-%' SETTINGS mutations_sync = 1")
    cases = ", ".join(f"video_id='{v['id']}', {ch_ts(v['post'])}" for v in vids.values())
    clickhouse(f"ALTER TABLE video_tags UPDATE published_at = multiIf({cases}, published_at) WHERE video_id IN ({id_list}) SETTINGS mutations_sync = 1")
    clickhouse(f"ALTER TABLE user_signup_events UPDATE occurred_at = {ch_ts(p['created'])} WHERE user_id = {owner} SETTINGS mutations_sync = 1")
    conds = [f"follower_id={f['follower']} AND following_id={f['following']} AND followed=1, {ch_ts(f['at'])}" for f in p["follows"]]
    conds += [f"follower_id={f['follower']} AND following_id={f['following']} AND followed=0, {ch_ts(f['unfollow'])}"
              for f in p["follows"] if f.get("unfollow")]
    for ch in chunks(conds, 300):
        clickhouse(f"ALTER TABLE follow_events UPDATE occurred_at = multiIf({', '.join(ch)}, occurred_at) "
                   f"WHERE (following_id = {owner} OR follower_id = {owner}) AND occurred_at >= {ch_ts(w0)} SETTINGS mutations_sync = 1")
    log(f"clickhouse: {len(new_eng)} engagement rows, {len(moved)} watch rows moved, {len(anon)} anonymous plays, {len(p['follows'])} follows")

    # ---- Cassandra
    lines = []
    for c, v in vids.items():
        for cm in v["comments"]:
            if res.get(cm["key"]):
                lines.append(f"UPDATE comments_by_video SET created_at={cql_ts(cm['at'])} WHERE video_id={v['id']} AND comment_id={res[cm['key']]};")
        for a in v["acts"]:
            sid = res.get(f"share:{c}:{a['u']}")
            if a["kind"] == "share" and sid:
                lines.append(f"UPDATE shares_by_video SET created_at={cql_ts(a['at'])} WHERE video_id={v['id']} AND share_id={sid};")
    for (kind, cid, u), at in cass.items():
        if kind == "comment_like":
            lines.append(f"UPDATE comment_likes SET created_at={cql_ts(at)} WHERE comment_id={cid} AND user_id={u};")
    for kind, member, cols, listing in (("like", "likes_by_video", ("video_id", "user_id", "created_at"), "likes_by_user"),
                                        ("repost", "reposts_by_video", ("video_id", "user_id", "created_at"), "reposts_by_user"),
                                        ("save", "saves_by_user", ("user_id", "video_id", "created_at"), "saves_by_user_time")):
        old = {(r[0], r[1]): r[2].strip() for r in cql_copy(member, cols) if len(r) == 3}
        for (k, vid, u), at in cass.items():
            key = (vid, u) if cols[0] == "video_id" else (u, vid)
            if k != kind or key not in old or old[key][:19].replace(" ", "T") < w0[:19]:
                continue  # not there, or already moved by an earlier run
            lines.append(f"DELETE FROM {listing} WHERE user_id={u} AND created_at='{old[key]}' AND video_id={vid};")
            lines.append(f"INSERT INTO {listing} (user_id, created_at, video_id) VALUES ({u}, {cql_ts(at)}, {vid});")
            where = f"video_id={vid} AND user_id={u}" if cols[0] == "video_id" else f"user_id={u} AND video_id={vid}"
            lines.append(f"UPDATE {member} SET created_at={cql_ts(at)} WHERE {where};")
    if not st.d["done"].get("anon_counted"):  # counters only add: flag first so a crash never doubles them
        st.d["done"]["anon_counted"] = True
        st.save()
        lines += [f"UPDATE video_counters SET view_count = view_count + {len(v['anon'])} WHERE video_id={v['id']};" for v in vids.values()]
        mongosh("video_db", "db.videos.bulkWrite([" + ",".join(
            f'{{updateOne: {{filter: {{_id: "{v["id"]}"}}, update: {{$inc: {{viewCount: {len(v["anon"])}}}}}}}}}' for v in vids.values()) + "]);")
    for ch in chunks(lines, 2000):
        cqlsh("\n".join(ch))
    log(f"cassandra: {len(lines)} statements")

    # ---- Mongo videos, ES, Redis
    ops = []
    for v in vids.values():
        sets = {"createdAt": mongo_ts(v["post"]), "updatedAt": mongo_ts(shift(v["post"], 90)), "publishedAt": mongo_ts(shift(v["post"], 90)),
                '"moderation.checkedAt"': mongo_ts(shift(v["post"], 60)), '"textModeration.checkedAt"': mongo_ts(v["post"])}
        ops.append(f'{{updateOne: {{filter: {{_id: "{v["id"]}"}}, update: {{$set: {{{", ".join(f"{k}: {x}" for k, x in sets.items())}}}}}}}}}')
    mongosh("video_db", f"db.videos.bulkWrite([{','.join(ops)}], {{ordered: false}});")
    docs = json_rows(mongosh("video_db", f'db.videos.find({{_id: {{$in: [{id_list}]}}}}, {{tags: 1, viewCount: 1}})'
                                         f'.forEach(v => print(JSON.stringify({{id: v._id, tags: v.tags || [], views: Number(v.viewCount || 0)}})));'))
    es, rl = [], []
    for d in docs:
        v = by_id[d["id"]]
        es += [json.dumps({"update": {"_index": "videos", "_id": d["id"]}}),
               json.dumps({"doc": {"createdAt": es_ts(v["post"]), "viewCount": d["views"]}})]
        e = int(parse(v["post"]).timestamp())
        rl += [f"ZADD reco:video:published XX {e} {d['id']}"] + [f"ZADD reco:tag:{t} XX {e} {d['id']}" for t in d["tags"]]
        rl.append(f"DEL video:{d['id']} interaction:counters:{d['id']}")
    es_bulk("\n".join(es) + "\n")
    redis(rl)
    log(f"videos: {len(ops)} mongo, {len(docs)} es, {len(rl)} redis")

    # ---- Postgres: the account, follows
    sql = [f"UPDATE user_follows SET created_at='{f['at']}', updated_at='{f.get('unfollow', f['at'])}'"
           + (f", deleted_at='{f['unfollow']}'" if f.get("unfollow") else "")
           + f" WHERE follower_id={f['follower']} AND following_id={f['following']} AND created_at >= '{w0}';" for f in p["follows"]]
    sql.append(f"UPDATE user_profiles SET created_at='{p['created']}' WHERE user_id={owner};")
    psql("tt-postgres-user", "user_db", "\n".join(sql))
    psql("tt-postgres-auth", "auth_db", f"UPDATE users SET created_at='{p['created']}', email_verified_at='{p['created']}' "
                                         f"WHERE id={owner} AND created_at > '{p['created']}';")
    log(f"postgres: {len(p['follows'])} follows, account created {p['created'][:10]}")

    # ---- notifications: the owner's (likes, comments, follows...) and the commenters' (replies, hearts)
    earliest, comment_dates = {}, defaultdict(list)

    def put(recipient, actor, typ, ref, at):
        if str(recipient) == str(actor):
            return
        k = (str(recipient), str(actor), typ, str(ref))
        if typ == "COMMENT":
            comment_dates[k].append(at)
        elif k not in earliest or at < earliest[k]:
            earliest[k] = at

    for v in vids.values():
        for a in v["acts"]:
            put(owner, a["u"], a["kind"].upper(), v["id"], a["at"])
        author = {cm["key"]: cm["u"] for cm in v["comments"]}
        for cm in v["comments"]:
            if res.get(cm["key"]):
                put(author.get(cm.get("parent"), owner), cm["u"], "COMMENT", v["id"], cm["at"])
        for x in v["comment_likes"]:
            if res.get(x["key"]):
                put(author[x["key"]], x["u"], "COMMENT_LIKE", v["id"], x["at"])
    for f in p["follows"]:
        put(f["following"], f["follower"], "NEW_FOLLOWER", f["follower"], f["at"])
    ops = [f'{{updateMany: {{filter: {{recipientId: NumberLong("{r}"), actorId: NumberLong("{a}"), type: "{t}", referenceId: "{ref}", '
           f'createdAt: {{$gte: {mongo_ts(w0)}}}}}, update: {{$set: {{createdAt: {mongo_ts(at)}}}}}}}}}' for (r, a, t, ref), at in earliest.items()]
    for ch in chunks(ops, 2000):
        mongosh("notification_db", f"db.notifications.bulkWrite([{','.join(ch)}], {{ordered: false}});")
    groups = [[r, a, ref, sorted(d)] for (r, a, t, ref), d in comment_dates.items()]
    out = mongosh("notification_db", "const groups = " + json.dumps(groups) + ";\n"
                  "let n = 0; groups.forEach(([r, a, ref, dates]) => {\n"
                  "  const docs = db.notifications.find({recipientId: NumberLong(r), actorId: NumberLong(a), type: 'COMMENT', referenceId: ref, "
                  f"createdAt: {{$gte: {mongo_ts(w0)}}}}}).sort({{_id: 1}}).toArray();\n"
                  "  dates.forEach((d, i) => { if (docs[i]) { db.notifications.updateOne({_id: docs[i]._id}, {$set: {createdAt: new Date(d)}}); n++; } });\n"
                  "});\nprint('comment notifications: ' + n);")
    log(f"notifications: {len(ops)} updates, {out.strip()}")
    st.d["done"]["backdate"] = True
    st.save()


# ------------------------------------------------------------------------------------------- main

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--owner", default="vominhhung031")
    ap.add_argument("--media", default="~/Projects/EProject/EProject4/studio-media")
    ap.add_argument("--only", help="comma-separated phases: prep,upload,plan,birthdates,engage,settings,backdate")
    args = ap.parse_args()
    media = Path(args.media).expanduser()
    st = State(media / "studio-ledger.json")
    if (media / "clips.json").exists():
        CLIPS.update({k: tuple(v) for k, v in json.loads((media / "clips.json").read_text()).items()})
    only = set(args.only.split(",")) if args.only else None

    def want(phase):
        return only is None or phase in only

    owner = int(psql("tt-postgres-auth", "auth_db", f"SELECT id FROM users WHERE username='{args.owner}';").strip())
    ledger_ids = [str(r["id"]) for r in json.loads(MAIN_LEDGER.read_text())["users"].values()]
    rows = psql("tt-postgres-auth", "auth_db", f"SELECT id, created_at FROM users WHERE status='ACTIVE' AND role='USER' AND email_verified "
                                               f"AND id IN ({','.join(ledger_ids)}) AND id <> {owner};").split("\n")
    joined = {int(r.split("|")[0]): parse(r.split("|")[1][:19].replace(" ", "T") + ".000Z") for r in rows if r.strip()}
    pool = sorted(joined)
    tok = Tokens()
    st.d.setdefault("started", iso(datetime.now(timezone.utc) - timedelta(minutes=5)))
    st.d.setdefault("end", iso(datetime.now(timezone.utc).replace(second=0, microsecond=0)))
    st.save()
    log(f"studio: owner {owner}, {len(pool)} personas")

    if want("prep"):
        prep(media, st)
    if want("upload"):
        upload(media, st, owner, tok)
    if want("plan") and "plan" not in st.d:
        plan(st, owner, pool, joined)
    if want("birthdates"):
        birthdates(pool, tok)
    if want("engage"):
        engage(st, owner, tok)
    if want("settings"):
        settings(st, owner, tok)
    if want("backdate") and not st.d["done"].get("backdate"):
        backdate(st, owner)
    log("studio: done")


if __name__ == "__main__":
    main()
