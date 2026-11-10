"""Last phase of the demo seed: what the admin console has open on demo day.

    python3 -I seed.py <media-dir> --phase realism      # after the other phases (make seed runs it)
    python3 -I realism.py --check                       # self-test of the plan, no services

Everything goes through the real services, so the console shows what they decided:
- flagged clips (SEED_FLAGGED, default ~/Downloads/tieucuc) and one explicit clip (SEED_EXPLICIT, default
  ~/Downloads/test.mp4) are uploaded and moderated by media-worker + moderation-service. What they hold is
  the review queue, what they reject is rejected, and what they publish people then report;
- reported comments, direct messages and accounts are real rows written by the accounts that did it,
  reported by the accounts that saw it, at severities from spam (2) to self-harm (10);
- new sign-ups that never verified, closed accounts, and videos their owners deleted.

Nothing here is decided by the admin: the open queue is left open. Times are kept as ages (days before
the end of the seed) and written by backdate.py, so `--grow` keeps this the most recent week.
"""
import json
import os
import sys
import time
import uuid
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

FLAGGED_DIR = Path(os.environ.get("SEED_FLAGGED", "~/Downloads/tieucuc")).expanduser()
EXPLICIT = Path(os.environ.get("SEED_EXPLICIT", "~/Downloads/test.mp4")).expanduser()

# --------------------------------------------------------------------------------------------- plan

# Accounts that exist only for this: who posts the flagged clips, the scams and the impersonation.
# (username, display name, bio, age in days, verified)
NEW_ACCOUNTS = [
    ("phim.ma.edit", "Phim Ma Edit", "Edit cảnh phim kinh dị mỗi tối 👻", 40, True),
    ("drama.24h", "Drama 24h", "Hóng biến mỗi ngày. Có clip gửi qua tin nhắn.", 25, True),
    ("kiemtien.cung.huy", "Huy Kiếm Tiền", "Từ 0 đồng lên 80 triệu/tháng. Học cùng Huy 👇", 9, True),
    ("minhanh.vlog.official", None, None, 6, True),  # copies the demo creator's name, bio and avatar
    ("tuyen.ctv.24h", "Tuyển CTV 24h", "Việc làm tại nhà, thu nhập 300-500k mỗi ngày", 3, True),
    ("clip.hot.moingay", "Clip Hot Mỗi Ngày", "Toàn clip hay, xem full ở trang cá nhân 🔞", 2, True),
    # signed up, never typed the code from the email
    ("hoangnam.2008", None, None, 1.5, False),
    ("ngochan.0912", None, None, 0.7, False),
    ("trungkien.vn98", None, None, 4.2, False),
    ("baotran.k", None, None, 2.6, False),
    ("le.duy.98", None, None, 5.5, False),
]

# What moderation-service decided for these files when this was written: 7 held (gore, violence,
# accident, gambling), 3 published, test.mp4 rejected (nsfw 0.99). The phase reacts to whatever it
# decides this time. (file, owner, title, hashtags, age in days, reports if published, reactions if published)
FLAGGED = [
    ("370241584471490560.mp4", "phim.ma.edit", "Cảnh này trong phim xem xong ám ảnh mấy ngày 😨", ["phimkinhdi", "edit", "horror"], 1.2, [], []),
    ("370241869705134080.mp4", "drama.24h", "Đánh ghen ở bar tối qua, quay được nguyên cảnh 😳", ["danhghen", "drama", "hongbien"], 2.4, [], []),
    ("370241980808052736.mp4", "hanoi.coffee", "Camera quán ghi lại lúc 2h sáng, ai nhận ra người này báo giúp mình với 🙏",
     ["camera", "canhbao", "quancafe"], 0.6, [], []),
    ("370242176166150144.mp4", "phim.ma.edit", "Đừng xem video này lúc 12h đêm 👹", ["kinhdi", "jumpscare", "horror"], 3.5,
     [("Shocking and graphic content", 3)], ["Giật cả mình 😱", "Ai xem lúc 12h đêm điểm danh", "Ghê quá không dám xem lại", "Tắt đèn xem đúng là sai lầm"]),
    ("370242365165682688.mp4", "saigon.by.night", "Xe bốc cháy trên cao tốc chiều nay, mọi người đi đường cẩn thận",
     ["tainan", "chayxe", "caotoc"], 0.3, [], []),
    ("370245261169016832.mp4", "tien.long.sport", "Tối nay anh em vào kèo nào? App này rút tiền về ví trong 5 phút 💸",
     ["bongda", "ngoaihanganh"], 1.8, [], []),
    ("370245561778978816.mp4", "khoa.skate", "Review pod mới vị dưa lưới, kéo một hơi mát cả họng 💨", ["vape", "pod", "review"], 4.2,
     [("Illegal activities and regulated goods", 2)], ["Mua ở đâu vậy anh", "Hút cái này hại phổi lắm bạn ơi", "Vị này thơm thật"]),
    ("370245722164969472.mp4", "duc.moto", "Vừa va chạm xong, may mà người không sao… mọi người chạy chậm thôi 😞",
     ["tainan", "antoangiaothong"], 0.9, [], []),
    ("370248320775372800.mp4", "kiemtien.cung.huy", "Tháng này về 80 triệu chỉ với cái điện thoại, ai muốn học thì để lại chữ \"học\" 💰",
     ["kiemtien", "lamgiau", "thunhapthudong"], 5.0,
     [("Frauds and scams", 4)], ["học", "Học ạ", "học anh ơi", "Cho em xin cách với anh", "Lừa đảo đấy mọi người đừng tin"]),
    ("370248502862692352.mp4", "tuan.kiet.night", "Cuối tuần chill cùng anh em 💜", ["chill", "party", "cuoituan"], 1.4, [], []),
    (None, "clip.hot.moingay", "Full clip không che ở trang cá nhân 🔞", ["clip", "hot"], 1.6, [], []),  # EXPLICIT
]

# Comments people reported, still open: (key, text, author or None = a viewer, reason, reporters, age in days).
# Every text was posted for real against the rules + classifier and came back VISIBLE: a report is the
# only way anyone sees them.
REPORTED_COMMENTS = [
    ("selfharm", "Nghe bài này xong chỉ muốn biến mất luôn, chắc mai mình không còn ở đây nữa thì cũng chẳng ai để ý", None,
     "Suicide and self-harm", 1, 0.4),
    ("threat", "Biết nhà mày ở đâu rồi đấy, tối nay ra đầu ngõ mà đón", None, "Violence, abuse, and criminal exploitation", 2, 1.1),
    ("doxx", "Con này tên thật là Nguyễn Thị Hạnh, học 11A2 trường Lê Lợi, ai cần thì tìm", None, "Sharing personal information", 1, 2.2),
    ("harass1", "Nhìn cái mặt đã thấy không ưa, đừng đăng nữa", None, "Hate and harassment", 3, 0.8),
    ("harass2", "Hát thế này mà cũng dám đăng, nghe mà nhức đầu", None, "Hate and harassment", 1, 3.0),
    ("prize", "Chúc mừng bạn đã trúng iPhone 17 từ chương trình tri ân, nhắn tin cho page để nhận quà nhé", "tuyen.ctv.24h",
     "Frauds and scams", 2, 2.6),
    ("cure", "Uống nước chanh nóng mỗi sáng là khỏi hẳn ung thư nha mọi người, bác sĩ giấu đấy", None, "Misinformation", 1, 3.4),
    ("promo", "Kênh mình mới lập, mọi người ghé ủng hộ với ạ", None, "Deceptive behavior and spam", 1, 0.5),
    ("crowd", "Quay ở đâu mà đông người thế, lần sau né giờ này", None, "Hate and harassment", 1, 1.9),  # nothing wrong with it
]

# Message requests from strangers: (sender, text, recipients, how many of them report it, reason, age in days)
DMS = [
    ("clip.hot.moingay", "Hi anh, em có clip riêng tư nè, muốn xem full thì nhắn em nha 😘", 4, 2, "Nudity and sexual content", 1.3),
    ("tuyen.ctv.24h", "Chào bạn, bên mình đang tuyển cộng tác viên làm tại nhà, mỗi ngày 300-500k, chỉ cần điện thoại. Bạn quan tâm không?",
     5, 2, "Frauds and scams", 2.1),
]

# Profile reports, still open: (account, reason, reporters, age in days). The impersonated creator reports first.
REPORTED_USERS = [
    ("minhanh.vlog.official", "Deceptive behavior and spam", 2, 1.0),
    ("kiemtien.cung.huy", "Frauds and scams", 1, 2.0),
    ("clip.hot.moingay", "Nudity and sexual content", 1, 0.9),
]

# An ordinary video someone reported anyway: open, and the right answer is to dismiss it.
BOGUS_VIDEO_REPORTS = [("dance", "Intellectual property violation", 2.8)]

CLOSED = [("deactivate", 3.0), ("deactivate", 12.0), ("delete", 6.0)]  # (how, age in days)
OWNER_DELETES = [1.5, 4.0, 8.0]  # creators deleting one of their own least-watched clips


def picks(plan, ledger, seed=2026):
    """Who writes, who reports and on which video: chosen once, saved, so a resumed run picks the same."""
    import random
    import content as C
    rnd = random.Random(seed + 7)
    users = {u["username"]: u for u in plan["users"]}
    taken = {name for name, *_ in NEW_ACCOUNTS} | {f[1] for f in FLAGGED}
    banned = {p["username"] for p in C.PERSONAS if p["spam"] or p["temp_ban"]}
    history = {a.get("target") for a in plan["admin"] if a["kind"] in ("warn", "revoke_sessions")}
    viewers = sorted(n for n, u in users.items() if u["role"] == "viewer" and not u["private"] and n not in banned
                     and n not in taken and n not in history)
    rnd.shuffle(viewers)
    pool = iter(viewers)
    blocked = {(b["blocker"], b["blocked"]) for b in ledger.blocks} | {(b["blocked"], b["blocker"]) for b in ledger.blocks}
    published = [k for k, v in ledger.videos.items() if v["status"] == "PUBLISHED" and v.get("visibility", "PUBLIC") == "PUBLIC"
                 and users[v["owner"]]["role"] == "creator" and not users[v["owner"]]["keyword_filter"]]
    top = sorted(published, key=lambda k: -ledger.videos[k]["views"])[:60]
    out = {"closed": [next(pool) for _ in CLOSED], "comments": {}, "dms": {}, "users": {}, "flagged_reporters": {}, "bogus": []}
    for key, _text, author, _reason, n, _age in REPORTED_COMMENTS:
        author = author or next(pool)
        video = rnd.choice([k for k in top if (author, ledger.videos[k]["owner"]) not in blocked])
        out["comments"][key] = {"author": author, "video": video,
                                "reporters": [next(p for p in pool if (p, ledger.videos[video]["owner"]) not in blocked) for _ in range(n)]}
    for sender, _text, n, k, _reason, _age in DMS:
        to = [next(pool) for _ in range(n)]
        out["dms"][sender] = {"to": to, "reporters": to[:k]}
    for name, _reason, n, _age in REPORTED_USERS:
        first = [plan["users"][0]["username"]] if name == "minhanh.vlog.official" else []
        out["users"][name] = first + [next(pool) for _ in range(n - len(first))]
    for f in FLAGGED:
        out["flagged_reporters"][f[0] or "explicit"] = [next(pool) for _ in range(sum(n for _, n in f[5]) + len(f[6]))]
    for topic, _reason, _age in BOGUS_VIDEO_REPORTS:
        video = rnd.choice([k for k in published if topic in "".join(ledger.videos[k]["tags"])] or published)
        out["bogus"].append({"video": video, "reporter": next(pool)})
    creators = sorted({ledger.videos[k]["owner"] for k in published} - {plan["users"][0]["username"]})
    out["deletes"] = []
    for owner in rnd.sample(creators, len(OWNER_DELETES)):
        mine = sorted((k for k in published if ledger.videos[k]["owner"] == owner), key=lambda k: ledger.videos[k]["views"])
        out["deletes"].append(mine[0])
    return out


# --------------------------------------------------------------------------------------------- run

def run(plan, ledger, media_dir):
    import seed as S
    R = ledger.realism
    for k in ("users", "flagged", "comments", "reports", "dms", "deleted", "closed_at"):
        R.setdefault(k, {})
    R.setdefault("closed", [])
    if "picks" not in R:
        R["picks"] = picks(plan, ledger)
        ledger.save()
    P = R["picks"]

    def tok(name):
        return S.fresh_token(R["users"][name], name) if name in R["users"] else S.token_of(ledger, name)

    def uid(name):
        return R["users"][name]["id"] if name in R["users"] else S.uid_of(ledger, name)

    def report(key, reporter, body, age, path="/api/v1/admin/reports"):
        if key in R["reports"]:
            return
        try:
            r = S.admin_api.call("POST", path, body, token=tok(reporter))
            R["reports"][key] = {"id": r["id"], "age_days": age}
        except S.ApiError as e:
            S.log(f"realism: report {key} skipped: {e}")
            R["reports"][key] = None
        ledger.save()

    # 1. accounts
    manifest = json.loads((Path(media_dir) / "manifest.json").read_text())
    demo = plan["users"][0]
    for i, (name, display, bio, age, verified) in enumerate(NEW_ACCOUNTS):
        rec = R["users"].setdefault(name, {"age_days": age, "verified": verified})
        if "id" not in rec:
            r = S.auth.call("POST", "/api/v1/auth/register", {"username": name, "email": f"{name}@example.com",
                                                              "password": S.PASSWORD, "turnstileToken": S.TURNSTILE},
                            headers=S.origin_headers(name))
            rec["id"] = int(r["id"])
            ledger.save()
        if not verified or rec.get("profile_done"):
            continue
        S.sql("tt-postgres-auth", "auth_db", f"UPDATE users SET email_verified=true, email_verified_at=now() WHERE id={rec['id']};")
        rec["token"], rec["refresh"] = S.login(name)
        rec["token_at"] = time.time()
        for _ in range(120):
            try:
                S.user_api.call("GET", f"/api/v1/users/{rec['id']}", token=rec["token"])
                break
            except S.ApiError as e:
                if e.status != 404:
                    raise
                time.sleep(0.5)
        avatar = demo["avatar"] if name == "minhanh.vlog.official" else manifest["avatars"][-1 - i]["file"]
        S.user_api.multipart("/api/v1/users/me/avatar", ("file", "avatar.jpg", (Path(media_dir) / avatar).read_bytes(), "image/jpeg"),
                             token=rec["token"])
        S.user_api.call("PATCH", "/api/v1/users/me", {"displayName": display or demo["display_name"], "bio": bio or demo["bio"]},
                        token=rec["token"])
        rec["profile_done"] = True
        ledger.save()
    S.log(f"realism: {len(R['users'])} accounts")

    # 2. flagged clips through the real pipeline
    for f in FLAGGED:
        file, owner, title, tags, age = f[:5]
        key = file or "explicit"
        if key in R["flagged"]:
            continue
        path = FLAGGED_DIR / file if file else EXPLICIT
        if not path.exists():
            S.log(f"realism: {path} missing, skipped")
            continue
        u = S.video_api.call("POST", "/api/v1/videos/upload-url", {"contentType": "video/mp4"}, token=tok(owner))
        S.post_minio(u["uploadUrl"], u["formFields"], path.read_bytes(), "video/mp4", filename=path.name)
        r = S.video_api.call("POST", "/api/v1/videos", {"title": title, "description": "", "rawFileUrl": u["fileUrl"],
                                                         "visibility": "PUBLIC", "tags": tags}, token=tok(owner))
        R["flagged"][key] = {"id": r["id"], "owner": owner, "status": r["status"], "age_days": age}
        ledger.save()
    deadline = time.time() + 30 * 60
    while any(v["status"] in S.WAITING for v in R["flagged"].values()):
        if time.time() > deadline:
            raise RuntimeError("flagged clips still in the pipeline after 30 min: is media-worker / moderation-service up?")
        time.sleep(10)
        for v in R["flagged"].values():
            if v["status"] in S.WAITING:
                v["status"] = S.video_api.call("GET", f"/api/v1/videos/{v['id']}", token=tok(v["owner"]))["status"]
        ledger.save()
    S.log("realism: flagged clips " + ", ".join(f"{k[-8:]}={v['status']}" for k, v in R["flagged"].items()))

    # 3. what people do with the ones that got published: react, then report
    for f in FLAGGED:
        key, reports, reactions = f[0] or "explicit", f[5], f[6]
        v = R["flagged"].get(key)
        if not v or v["status"] != "PUBLISHED":
            continue
        people = list(P["flagged_reporters"][key])
        base = f"/api/v1/interactions/videos/{v['id']}"
        for n, text in enumerate(reactions):
            ck = f"react:{key}:{n}"
            if ck in R["comments"]:
                continue
            who = people[-1 - n]
            try:
                S.inter.call("POST", base + "/like", token=tok(who))
                r = S.inter.call("POST", base + "/comments", {"content": text}, token=tok(who))
                R["comments"][ck] = {"id": r["commentId"], "video_id": v["id"], "author": who,
                                     "age_days": v["age_days"] * (0.9 - 0.7 * n / len(reactions))}
            except S.ApiError as e:
                S.log(f"realism: reaction {ck} skipped: {e}")
                R["comments"][ck] = None
            ledger.save()
            time.sleep(3.3)
        i = 0
        for reason, n in reports:
            for j in range(n):
                report(f"video:{key}:{j}", people[i], {"targetType": "VIDEO", "targetId": v["id"], "reason": reason},
                       v["age_days"] * (0.8 - 0.7 * j / n))
                i += 1
    for n, b in enumerate(P["bogus"]):
        _topic, reason, age = BOGUS_VIDEO_REPORTS[n]
        report(f"bogus:{n}", b["reporter"], {"targetType": "VIDEO", "targetId": ledger.videos[b["video"]]["id"], "reason": reason}, age)

    # 4. reported comments
    for key, text, _author, reason, _n, age in REPORTED_COMMENTS:
        pk = P["comments"][key]
        if key not in R["comments"]:
            vid = ledger.videos[pk["video"]]["id"]
            try:
                r = S.inter.call("POST", f"/api/v1/interactions/videos/{vid}/comments", {"content": text}, token=tok(pk["author"]))
                R["comments"][key] = {"id": r["commentId"], "video_id": vid, "author": pk["author"], "age_days": age,
                                      "status": r.get("status") or "VISIBLE"}
            except S.ApiError as e:
                S.log(f"realism: comment {key} refused: {e}")
                R["comments"][key] = None
            ledger.save()
            time.sleep(3.3)
        c = R["comments"][key]
        if not c or c.get("status") != "VISIBLE":
            S.log(f"realism: comment {key} not visible ({c and c.get('status')}), no report")
            continue
        for j, who in enumerate(pk["reporters"]):
            report(f"comment:{key}:{j}", who, {"targetType": "COMMENT", "targetId": f"{c['video_id']}:{c['id']}", "reason": reason},
                   age * (0.9 - 0.5 * j / len(pk["reporters"])))

    # 5. message requests from strangers, reported from the conversation
    for sender, text, _n, _k, reason, age in DMS:
        pk = P["dms"][sender]
        for j, to in enumerate(pk["to"]):
            dk = f"{sender}:{to}"
            if dk not in R["dms"]:
                try:
                    conv = S.chat_api.call("POST", "/api/v1/conversations", {"participantId": uid(to)}, token=tok(sender))
                    m = S.chat_api.call("POST", f"/api/v1/conversations/{conv['id']}/messages", {"content": text}, token=tok(sender))
                    R["dms"][dk] = {"conv": conv["id"], "msg": m["id"], "age_days": age * (1 - 0.15 * j)}
                except S.ApiError as e:
                    S.log(f"realism: message {dk} skipped: {e}")
                    R["dms"][dk] = None
                ledger.save()
            d = R["dms"][dk]
            if d and to in pk["reporters"]:
                report(f"dm:{dk}", to, {"conversationId": d["conv"], "reason": reason, "messageIds": [d["msg"]],
                                        "requestId": uuid.uuid5(uuid.NAMESPACE_URL, dk).hex}, d["age_days"] * 0.7,
                       path="/api/v1/admin/reports/direct-message")

    # 6. profile reports
    for name, reason, _n, age in REPORTED_USERS:
        for j, who in enumerate(P["users"][name]):
            report(f"user:{name}:{j}", who, {"targetType": "USER", "targetId": str(uid(name)), "reason": reason}, age * (1 - 0.4 * j))

    # 7. accounts their owners closed, videos their owners deleted
    for (how, age), name in zip(CLOSED, P["closed"]):
        if name in R["closed"]:
            continue
        try:
            S.auth.call("POST", f"/api/v1/auth/account/{how}", {"password": S.PASSWORD}, token=tok(name))
        except S.ApiError as e:
            S.log(f"realism: {how} {name} failed: {e}")
            continue
        R["closed"].append(name)
        R["closed_at"][name] = {"how": how, "age_days": age}
        ledger.save()
    for key, age in zip(P["deletes"], OWNER_DELETES):
        if key in R["deleted"]:
            continue
        rec = ledger.videos[key]
        S.video_api.call("DELETE", f"/api/v1/videos/{rec['id']}", token=tok(rec["owner"]))
        rec["status"] = "DELETED"
        R["deleted"][key] = {"video_id": rec["id"], "age_days": age}
        ledger.save()
    opened = sum(1 for v in R["reports"].values() if v)
    S.log(f"realism: {opened} open reports, {len(R['closed'])} closed accounts, {len(R['deleted'])} deleted videos")
    ledger.phase_done["realism"] = True
    ledger.save()


# --------------------------------------------------------------------------------------------- backdate

def backdate(ledger, end):
    """Write the ages as times before `end` (ledger t1, or now with --grow). Idempotent."""
    from datetime import timedelta
    import backdate as B
    R = ledger.realism
    if not R.get("users"):
        return

    def at(age):
        return B.iso(end - timedelta(days=age))

    auth, user = [], []
    for rec in R["users"].values():
        if "id" not in rec:
            continue
        t = B.pg_ts(at(rec["age_days"]))
        verified = f", email_verified_at={t}, last_login_at={B.pg_ts(at(rec['age_days'] * 0.2))}" if rec["verified"] else ""
        auth.append(f"UPDATE users SET created_at={t}, updated_at={t}{verified} WHERE id={rec['id']};")
        auth.append(spread_events(rec["id"], at(rec["age_days"]), at(rec["age_days"] * 0.2)))
        user.append(f"UPDATE user_profiles SET created_at={t}, updated_at={t} WHERE user_id={rec['id']};")
    for name, c in R.get("closed_at", {}).items():
        t = B.pg_ts(at(c["age_days"]))
        extra = f", deletion_scheduled_at={t} + interval '30 days'" if c["how"] == "delete" else ""
        auth.append(f"UPDATE users SET deactivated_at={t}{extra} WHERE id={ledger.users[name]['id']} AND deactivated_at IS NOT NULL;")
    B.psql("tt-postgres-auth", "auth_db", "\n".join(auth))
    B.psql("tt-postgres-user", "user_db", "\n".join(user))

    ops = []
    for v in R["flagged"].values():
        t = at(v["age_days"])
        sets = {"createdAt": B.mongo_ts(t), "updatedAt": B.mongo_ts(B.shift(t, 90)), '"moderation.checkedAt"': B.mongo_ts(B.shift(t, 60)),
                '"textModeration.checkedAt"': B.mongo_ts(t)}
        if v["status"] == "PUBLISHED":
            sets["publishedAt"] = B.mongo_ts(B.shift(t, 90))
        body = ", ".join(f"{k}: {x}" for k, x in sets.items())
        ops.append(f'{{updateOne: {{filter: {{_id: "{v["id"]}"}}, update: {{$set: {{{body}}}}}}}}}')
    for d in R["deleted"].values():
        ops.append(f'{{updateOne: {{filter: {{_id: "{d["video_id"]}"}}, update: {{$set: {{deletedAt: {B.mongo_ts(at(d["age_days"]))}}}}}}}}}')
    if ops:
        B.mongosh("video_db", f"db.videos.bulkWrite([{','.join(ops)}], {{ordered: false}});")
    es = []
    for v in R["flagged"].values():
        if v["status"] == "PUBLISHED":
            es += [json.dumps({"update": {"_index": "videos", "_id": v["id"]}}), json.dumps({"doc": {"createdAt": B.es_ts(at(v["age_days"]))}})]
    if es:
        B.es_bulk("\n".join(es) + "\n")

    cql = [f"UPDATE comments_by_video SET created_at={B.cql_ts(at(c['age_days']))} WHERE video_id={c['video_id']} AND comment_id={c['id']};"
           for c in R["comments"].values() if c]
    if cql:
        B.cqlsh("\n".join(cql))

    msgs, convs = [], []
    for d in R["dms"].values():
        if d:
            t = B.mongo_ts(at(d["age_days"]))
            msgs.append(f'{{updateOne: {{filter: {{_id: "{d["msg"]}"}}, update: {{$set: {{sentAt: {t}}}}}}}}}')
            convs.append(f'{{updateOne: {{filter: {{_id: "{d["conv"]}"}}, update: {{$set: {{createdAt: {t}, lastMessageAt: {t}}}}}}}}}')
    if msgs:
        B.mongosh("chat_db", f"db.messages.bulkWrite([{','.join(msgs)}], {{ordered: false}});")
        B.mongosh("chat_db", f"db.conversations.bulkWrite([{','.join(convs)}], {{ordered: false}});")

    rows = [f"UPDATE reports SET created_at={B.pg_ts(at(r['age_days']))}, updated_at={B.pg_ts(at(r['age_days']))} "
            f"WHERE id={r['id']} AND status='PENDING';" for r in R["reports"].values() if r]
    if rows:
        B.psql("tt-postgres-admin", "admin_db", "\n".join(rows))
    B.log(f"realism: {len(auth)} auth, {len(ops)} video, {len(cql)} comment, {len(msgs)} message, {len(rows)} report updates")


def spread_events(user_id, start, stop):
    """One user's security events, kept in order, spread evenly from `start` to `stop` (ISO strings)."""
    return (f"WITH e AS (SELECT id, row_number() OVER (ORDER BY created_at, id) AS n, count(*) OVER () AS total "
            f"FROM auth_security_events WHERE user_id={user_id}) "
            f"UPDATE auth_security_events a SET created_at = TIMESTAMPTZ '{start}' + "
            f"(TIMESTAMPTZ '{stop}' - TIMESTAMPTZ '{start}') * (e.n::float / (e.total + 1)) FROM e WHERE a.id = e.id;")


# --------------------------------------------------------------------------------------------- check

def check():
    import re
    import content as C
    names = [n for n, *_ in NEW_ACCOUNTS]
    assert len(set(names)) == len(names) and all(re.fullmatch(r"[a-z][a-z0-9._]{2,49}", n) for n in names)
    personas = {p["username"]: p for p in C.PERSONAS}
    assert not set(names) & set(personas), "new accounts must not collide with personas"
    for f in FLAGGED:
        owner = f[1]
        assert owner in names or (owner in personas and not (personas[owner]["spam"] or personas[owner]["temp_ban"]
                                                            or personas[owner]["private"])), owner
        assert all(re.fullmatch(r"[a-z0-9]{2,50}", t) for t in f[3]), f[3]
        assert f[0] is None or (FLAGGED_DIR / f[0]).name == f[0]
    weights = {"Suicide and self-harm": 10, "Violence, abuse, and criminal exploitation": 9, "Nudity and sexual content": 8,
               "Sharing personal information": 7, "Hate and harassment": 6, "Shocking and graphic content": 5,
               "Illegal activities and regulated goods": 4, "Frauds and scams": 3, "Deceptive behavior and spam": 2,
               "Misinformation": 2, "Intellectual property violation": 1}  # admin-service V5__report_queue.sql
    reasons = ({c[3] for c in REPORTED_COMMENTS} | {u[1] for u in REPORTED_USERS} | {d[4] for d in DMS}
               | {r for f in FLAGGED for r, _ in f[5]} | {b[1] for b in BOGUS_VIDEO_REPORTS})
    assert reasons <= set(weights), reasons - set(weights)
    assert len({weights[r] for r in reasons}) >= 8, "the open queue should span the severities"
    ages = [c[5] for c in REPORTED_COMMENTS] + [u[3] for u in REPORTED_USERS] + [f[4] for f in FLAGGED] + [d[5] for d in DMS]
    assert max(ages) < 14, "StaleReportSweep closes expiring reasons after 30 days; keep the queue recent"
    for name in [u[0] for u in REPORTED_USERS] + [d[0] for d in DMS]:
        assert name in names, name
    owners_age = {n: a for n, _d, _b, a, _v in NEW_ACCOUNTS}
    assert all(f[4] < owners_age.get(f[1], 999) for f in FLAGGED), "a clip is posted after its owner signed up"
    assert all(d[5] < owners_age[d[0]] for d in DMS) and all(u[3] < owners_age[u[0]] for u in REPORTED_USERS)
    print("ok")


if __name__ == "__main__":
    if sys.argv[1:2] != ["--check"]:
        print(__doc__)
        sys.exit(2)
    check()
