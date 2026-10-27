#!/usr/bin/env python3
"""Create pass of the demo seed: plays scenario.build() against the real services, records ids in ledger.json.

    python3 -I seed.py <media-dir> [--seed 2026] [--phase users|videos|engagement|chat|admin|stories|all]
    python3 -I seed.py <media-dir> --refresh      # fresh engagement for trending (Task 8)
    python3 -I seed.py --selftest

Talks to service ports directly (not the gateway). Resumable: anything already in the ledger is skipped.
"""
import argparse
import json
import os
import subprocess
import sys
import time
import uuid
import urllib.error
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import scenario  # noqa: E402

AUTH, USER, VIDEO, INTERACTION, STORY, CHAT, ADMIN = (
    "http://localhost:8081", "http://localhost:8082", "http://localhost:8083", "http://localhost:8085",
    "http://localhost:8086", "http://localhost:8088", "http://localhost:8096")
TURNSTILE = "XXXX.DUMMY.TOKEN.XXXX"
PASSWORD = "Demo@12345"
REPO = Path(__file__).resolve().parents[2]


class ApiError(Exception):
    def __init__(self, status, code, message):
        super().__init__(f"{status} {code}: {message}")
        self.status, self.code, self.message = status, code, message


class Api:
    """Tiny JSON client over urllib. Unwraps ApiResponse.data, raises ApiError otherwise."""

    def __init__(self, base):
        self.base = base

    def call(self, method, path, body=None, token=None, expect=(200, 201, 204), retries=3):
        data = json.dumps(body).encode() if body is not None else None
        headers = {"Accept": "application/json"}
        if data is not None:
            headers["Content-Type"] = "application/json"
        if token:
            headers["Authorization"] = "Bearer " + token
        req = urllib.request.Request(self.base + path, data=data, method=method, headers=headers)
        for attempt in range(retries):
            try:
                with urllib.request.urlopen(req, timeout=120) as r:
                    raw = r.read()
                    if r.status not in expect:
                        raise ApiError(r.status, "UNEXPECTED_STATUS", raw[:200].decode(errors="replace"))
                    return json.loads(raw)["data"] if raw else None
            except urllib.error.HTTPError as e:
                raw = e.read()
                try:
                    j = json.loads(raw)
                    code, msg = j.get("code", ""), j.get("message", "")
                except Exception:  # noqa
                    code, msg = "", raw[:200].decode(errors="replace")
                if e.code in (502, 503, 504) and attempt + 1 < retries:
                    time.sleep(2 * (attempt + 1))
                    continue
                raise ApiError(e.code, code, msg) from None
            except (urllib.error.URLError, TimeoutError, ConnectionError) as e:
                if attempt + 1 < retries:
                    time.sleep(2 * (attempt + 1))
                    continue
                raise ApiError(0, "CONNECTION", str(e)) from None

    def multipart(self, path, file, token=None, fields=None):
        """file = (field_name, filename, bytes, content_type)."""
        boundary = "----seed" + uuid.uuid4().hex
        body = b""
        for k, v in (fields or {}).items():
            body += f"--{boundary}\r\nContent-Disposition: form-data; name=\"{k}\"\r\n\r\n{v}\r\n".encode()
        name, filename, blob, ctype = file
        body += (f"--{boundary}\r\nContent-Disposition: form-data; name=\"{name}\"; filename=\"{filename}\"\r\n"
                 f"Content-Type: {ctype}\r\n\r\n").encode() + blob + b"\r\n" + f"--{boundary}--\r\n".encode()
        headers = {"Content-Type": f"multipart/form-data; boundary={boundary}", "Accept": "application/json"}
        if token:
            headers["Authorization"] = "Bearer " + token
        req = urllib.request.Request(self.base + path, data=body, method="POST", headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=300) as r:
                raw = r.read()
                return json.loads(raw)["data"] if raw else None
        except urllib.error.HTTPError as e:
            raw = e.read()
            try:
                j = json.loads(raw)
            except ValueError:
                raise ApiError(e.code, "", raw[:300].decode(errors="replace")) from None
            raise ApiError(e.code, j.get("code", ""), j.get("message", "")) from None


def post_minio(upload_url, form_fields, blob, content_type, filename="upload"):
    boundary = "----seed" + uuid.uuid4().hex
    body = b""
    for k, v in form_fields.items():
        body += f"--{boundary}\r\nContent-Disposition: form-data; name=\"{k}\"\r\n\r\n{v}\r\n".encode()
    body += (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{filename}\"\r\n"
             f"Content-Type: {content_type}\r\n\r\n").encode() + blob + b"\r\n" + f"--{boundary}--\r\n".encode()
    req = urllib.request.Request(upload_url, data=body, method="POST",
                                 headers={"Content-Type": f"multipart/form-data; boundary={boundary}"})
    with urllib.request.urlopen(req, timeout=600) as r:
        if r.status not in (200, 201, 204):
            raise ApiError(r.status, "MINIO", r.read()[:300].decode(errors="replace"))


def sql(container, db, statement):
    """Run SQL through psql inside the container; returns stdout (tuples-only, unaligned)."""
    r = subprocess.run(["docker", "exec", "-i", container, "psql", "-U", "postgres", "-d", db, "-v", "ON_ERROR_STOP=1", "-tA"],
                       input=statement.encode(), capture_output=True, check=False)
    if r.returncode != 0:
        raise RuntimeError(r.stderr.decode())
    return r.stdout.decode()


class Ledger:
    LISTS = ("likes", "shares", "reposts", "saves", "comment_likes", "follows", "chats", "stories", "refresh")
    MAPS = ("users", "videos", "comments", "reports", "actions", "admin", "phase_done")

    def __init__(self, path):
        self.path = Path(path)
        self.data = json.loads(self.path.read_text()) if self.path.exists() else {}
        for s in self.LISTS:
            self.data.setdefault(s, [])
        for s in self.MAPS:
            self.data.setdefault(s, {})
        self._dirty = 0

    def __getattr__(self, name):
        if name in self.LISTS or name in self.MAPS:
            return self.data[name]
        raise AttributeError(name)

    def touch(self, every=25):
        self._dirty += 1
        if self._dirty >= every:
            self.save()

    def save(self):
        tmp = self.path.with_suffix(".tmp")
        tmp.write_text(json.dumps(self.data, ensure_ascii=False))
        tmp.replace(self.path)
        self._dirty = 0


auth, user_api, video_api, inter, story_api, chat_api, admin_api = (Api(b) for b in (AUTH, USER, VIDEO, INTERACTION, STORY, CHAT, ADMIN))


def log(msg):
    print(time.strftime("%H:%M:%S"), msg, flush=True)


def token_of(ledger, username):
    return ledger.users[username]["token"]


def uid_of(ledger, username):
    return ledger.users[username]["id"]


def login(username):
    t = auth.call("POST", "/api/v1/auth/login", {"usernameOrEmail": username, "password": PASSWORD})
    return t["accessToken"], t["refreshToken"]


# --------------------------------------------------------------------------------------------- users

def phase_users(plan, ledger, media_dir):
    new = 0
    for u in plan["users"]:
        name = u["username"]
        rec = ledger.users.setdefault(name, {})
        if "id" not in rec:
            r = auth.call("POST", "/api/v1/auth/register",
                          {"username": name, "email": f"{name}@example.com", "password": PASSWORD, "turnstileToken": TURNSTILE})
            rec["id"] = int(r["id"])
            rec["join_at"] = u["join_at"]
            sql("tt-postgres-auth", "auth_db", f"UPDATE users SET email_verified=true, email_verified_at=now() WHERE id={rec['id']};")
            new += 1
            ledger.touch()
        if "token" not in rec:
            rec["token"], rec["refresh"] = login(name)
            ledger.touch()
        if not rec.get("profile_done"):
            for _ in range(120):
                try:
                    user_api.call("GET", f"/api/v1/users/{rec['id']}", token=rec["token"])
                    break
                except ApiError as e:
                    if e.status != 404:
                        raise
                    time.sleep(0.5)
            else:
                raise RuntimeError(f"profile for {name} never appeared in user-service")
            blob = (Path(media_dir) / u["avatar"]).read_bytes()
            user_api.multipart("/api/v1/users/me/avatar", ("file", "avatar.jpg", blob, "image/jpeg"), token=rec["token"])
            user_api.call("PATCH", "/api/v1/users/me", {"displayName": u["display_name"], "bio": u["bio"]}, token=rec["token"])
            if u.get("settings"):
                user_api.call("PATCH", "/api/v1/users/me/settings", u["settings"], token=rec["token"])
            rec["profile_done"] = True
            ledger.touch()
    ledger.save()
    log(f"users: {new} new, {len(plan['users']) - new} existing")
    done = {(f["follower"], f["following"]) for f in ledger.follows}
    added = 0
    for f in plan["follows"]:
        if (f["follower"], f["following"]) in done:
            continue
        try:
            r = user_api.call("POST", f"/api/v1/users/{uid_of(ledger, f['following'])}/follow", token=token_of(ledger, f["follower"]))
            status = (r or {}).get("status", "FOLLOWING")
        except ApiError as e:
            if e.status == 409:
                status = "FOLLOWING"
            else:
                raise
        ledger.follows.append({"follower": f["follower"], "following": f["following"], "at": f["at"], "status": status})
        added += 1
        ledger.touch()
    ledger.save()
    log(f"follows: {added} new, {len(done)} existing")
    ledger.phase_done["users"] = True
    ledger.save()


# --------------------------------------------------------------------------------------------- videos

WAITING = ("PROCESSING", "PENDING_MODERATION")


def _upload_raw(args):
    """upload-url + MinIO POST for one video; returns fileUrl. Runs in a worker thread."""
    media_dir, v, token = args
    u = video_api.call("POST", "/api/v1/videos/upload-url", {"contentType": "video/mp4"}, token=token)
    blob = (Path(media_dir) / v["file"]).read_bytes()
    post_minio(u["uploadUrl"], u["formFields"], blob, "video/mp4", filename=Path(v["file"]).name)
    return u["fileUrl"]


def phase_videos(plan, ledger, media_dir):
    from concurrent.futures import ThreadPoolExecutor
    todo = [v for v in plan["videos"] if "id" not in ledger.videos.get(v["key"], {})]
    log(f"videos: {len(todo)} to create, {len(plan['videos']) - len(todo)} existing")
    with ThreadPoolExecutor(max_workers=3) as pool:
        uploads = pool.map(_upload_raw, [(media_dir, v, token_of(ledger, v["owner"])) for v in todo])
        for v, file_url in zip(todo, uploads):
            body = {"title": v["title"], "description": v["description"], "rawFileUrl": file_url,
                    "visibility": v["visibility"], "tags": v["tags"]}
            r = video_api.call("POST", "/api/v1/videos", body, token=token_of(ledger, v["owner"]))
            ledger.videos[v["key"]] = {"id": r["id"], "owner": v["owner"], "status": r["status"], "at": v["at"],
                                       "tags": v["tags"], "views": v["views"], "completions": v["completions"],
                                       "likes": v["likes"], "duration": v["duration"], "visibility": v["visibility"],
                                       "held": v["held"]}
            ledger.touch()
    ledger.save()
    _wait_for_pipeline(ledger)
    ledger.phase_done["videos"] = True
    ledger.save()


def _wait_for_pipeline(ledger, timeout_s=90 * 60):
    """Poll each owner's listing until none of their videos is PROCESSING/PENDING_MODERATION."""
    from collections import Counter
    by_owner = {}
    for key, rec in ledger.videos.items():
        by_owner.setdefault(rec["owner"], []).append(key)
    start, last_line = time.time(), 0
    while True:
        pending = 0
        for owner, keys in by_owner.items():
            if all(ledger.videos[k]["status"] not in WAITING for k in keys):
                continue
            uid, tok, page, seen = uid_of(ledger, owner), token_of(ledger, owner), 0, {}
            while True:
                r = video_api.call("GET", f"/api/v1/videos/users/{uid}?page={page}&size=50", token=tok)
                for item in r["content"]:
                    seen[item["id"]] = item["status"]
                page += 1
                if page >= r.get("totalPages", 1):
                    break
            for k in keys:
                st = seen.get(ledger.videos[k]["id"])
                if st:
                    ledger.videos[k]["status"] = st
                pending += ledger.videos[k]["status"] in WAITING
        ledger.save()
        c = Counter(v["status"] for v in ledger.videos.values())
        if pending == 0:
            log(f"pipeline done: {dict(c)}")
            return
        if time.time() - last_line > 60:
            log(f"waiting on {pending}: {dict(c)}")
            last_line = time.time()
        if time.time() - start > timeout_s:
            raise RuntimeError(f"pipeline still has {pending} videos pending after {timeout_s}s")
        time.sleep(10)


# --------------------------------------------------------------------------------------------- engagement

def _handle_comment_error(err, actor, muted):
    """422 COMMENT_REJECTED -> drop this one; COMMENTING_RESTRICTED -> mute the persona; else re-raise."""
    if err.code == "COMMENT_REJECTED" or err.code == "DUPLICATE_COMMENT":
        return
    if err.code == "COMMENTING_RESTRICTED":
        muted.add(actor)
        return
    raise err


class _CommentPacer:
    """Per-actor pacing under the service limits: 1 comment / 3.2 s and 9 per minute."""

    def __init__(self):
        self.last = {}
        self.minute = {}

    def wait(self, actor):
        now = time.time()
        q = [t for t in self.minute.get(actor, []) if now - t < 60]
        if len(q) >= 9:
            time.sleep(60 - (now - q[0]) + 0.1)
            now = time.time()
            q = [t for t in q if now - t < 60]
        gap = 3.2 - (now - self.last.get(actor, 0))
        if gap > 0:
            time.sleep(gap)
        self.last[actor] = time.time()
        q.append(self.last[actor])
        self.minute[actor] = q


def _engage_one(ev, ledger, muted, pacer, lock):
    """Send one planned event; returns a ledger entry or None when dropped."""
    vid = ledger.videos[ev["video"]]["id"]
    tok = token_of(ledger, ev["actor"])
    base = f"/api/v1/interactions/videos/{vid}"
    kind = ev["kind"]
    try:
        if kind == "like":
            inter.call("POST", base + "/like", token=tok)
            return {"actor": ev["actor"], "video": ev["video"], "at": ev["at"]}
        if kind in ("comment", "reply"):
            if ev["actor"] in muted:
                return None
            body = {"content": ev["text"]}
            if kind == "reply":
                with lock:
                    parent = ledger.comments.get(ev["parent_cid"])
                if not parent:
                    return None
                body["parentId"] = parent["id"]
            pacer.wait(ev["actor"])
            for attempt in range(2):
                try:
                    r = inter.call("POST", base + "/comments", body, token=tok)
                    break
                except ApiError as e:
                    if e.code == "COMMENT_RATE_LIMITED" and attempt == 0:
                        time.sleep(10)
                        continue
                    _handle_comment_error(e, ev["actor"], muted)
                    return None
            return {"id": r["commentId"], "video": ev["video"], "at": ev["at"], "author": ev["actor"],
                    "parent_cid": ev.get("parent_cid"), "status": r.get("status") or "VISIBLE"}
        if kind == "comment_like":
            with lock:
                c = ledger.comments.get(ev["cid"])
            if not c:
                return None
            inter.call("POST", f"{base}/comments/{c['id']}/like", token=tok)
            return {"actor": ev["actor"], "video": ev["video"], "cid": ev["cid"], "at": ev["at"]}
        if kind == "share":
            r = inter.call("POST", base + "/share", token=tok)
            return {"actor": ev["actor"], "video": ev["video"], "at": ev["at"], "id": r["shareId"]}
        if kind == "repost":
            inter.call("POST", base + "/repost", token=tok)
            return {"actor": ev["actor"], "video": ev["video"], "at": ev["at"]}
        if kind == "save":
            inter.call("POST", base + "/save", token=tok)
            return {"actor": ev["actor"], "video": ev["video"], "at": ev["at"]}
    except ApiError as e:
        if e.status in (404, 409) or e.code in ("LIKE_RATE_LIMITED", "COMMENT_LIKE_RATE_LIMITED", "SHARE_RATE_LIMITED",
                                                "REPOST_RATE_LIMITED", "SAVE_RATE_LIMITED", "INTERACTION_BLOCKED"):
            log(f"drop {kind} by {ev['actor']} on {ev['video']}: {e}")
            return None
        raise
    raise ValueError(kind)


def phase_engagement(plan, ledger):
    import threading
    from concurrent.futures import ThreadPoolExecutor
    done = {
        "like": {(x["actor"], x["video"]) for x in ledger.likes},
        "share": {(x["actor"], x["video"], x["at"]) for x in ledger.shares},
        "repost": {(x["actor"], x["video"]) for x in ledger.reposts},
        "save": {(x["actor"], x["video"]) for x in ledger.saves},
        "comment_like": {(x["actor"], x["cid"]) for x in ledger.comment_likes},
    }
    section = {"like": ledger.likes, "share": ledger.shares, "repost": ledger.reposts, "save": ledger.saves,
               "comment_like": ledger.comment_likes}

    def key(ev):
        k = ev["kind"]
        if k in ("comment", "reply"):
            return ev["cid"] in ledger.comments or ev.get("dropped")
        if k == "share":
            return (ev["actor"], ev["video"], ev["at"]) in done[k]
        if k == "comment_like":
            return (ev["actor"], ev["cid"]) in done[k]
        return (ev["actor"], ev["video"]) in done[k]

    todo = [ev for ev in plan["events"] if ledger.videos.get(ev["video"], {}).get("status") == "PUBLISHED" and not key(ev)]
    log(f"engagement: {len(todo)} events to send ({len(plan['events']) - len(todo)} skipped/done)")
    muted, pacer, lock = set(), _CommentPacer(), threading.Lock()
    buckets = [[] for _ in range(4)]
    for ev in todo:
        buckets[hash(ev["video"]) % 4].append(ev)
    counter = {"sent": 0, "dropped": 0}

    def run(bucket):
        for ev in bucket:
            rec = _engage_one(ev, ledger, muted, pacer, lock)
            with lock:
                if rec is None:
                    counter["dropped"] += 1
                elif ev["kind"] in ("comment", "reply"):
                    ledger.comments[ev["cid"]] = rec
                else:
                    section[ev["kind"]].append(rec)
                counter["sent"] += 1
                if counter["sent"] % 25 == 0:
                    ledger.save()
                if counter["sent"] % 500 == 0:
                    log(f"engagement: {counter['sent']}/{len(todo)} sent, {counter['dropped']} dropped, muted={sorted(muted)}")

    with ThreadPoolExecutor(max_workers=4) as pool:
        list(pool.map(run, buckets))
    ledger.save()
    log(f"engagement done: {counter['sent']} sent, {counter['dropped']} dropped, muted={sorted(muted)}")
    ledger.phase_done["engagement"] = True
    ledger.save()


# --------------------------------------------------------------------------------------------- chat + stories

def phase_chat(plan, ledger):
    existing = {(c["a"], c["b"]) for c in ledger.chats}
    for c in plan["chats"]:
        if (c["a"], c["b"]) in existing:
            continue
        conv = chat_api.call("POST", "/api/v1/conversations", {"participantId": uid_of(ledger, c["b"])}, token=token_of(ledger, c["a"]))
        msgs = []
        for m in c["messages"]:
            r = chat_api.call("POST", f"/api/v1/conversations/{conv['id']}/messages", {"content": m["text"]}, token=token_of(ledger, m["from"]))
            msgs.append({"id": r["id"], "from": m["from"], "at": m["at"]})
        ledger.chats.append({"id": conv["id"], "a": c["a"], "b": c["b"], "messages": msgs})
        ledger.save()
    log(f"chats: {len(ledger.chats)} conversations")
    ledger.phase_done["chat"] = True
    ledger.save()


def phase_stories(plan, ledger, media_dir):
    existing = {s["file"] for s in ledger.stories}
    for st in plan["stories"]:
        if st["file"] in existing:
            continue
        tok = token_of(ledger, st["owner"])
        u = story_api.call("POST", "/api/v1/stories/upload-url", {"contentType": "image/jpeg"}, token=tok)
        post_minio(u["uploadUrl"], u["formFields"], (Path(media_dir) / st["file"]).read_bytes(), "image/jpeg", filename="story.jpg")
        r = story_api.call("POST", "/api/v1/stories", {"mediaUrl": u["fileUrl"], "caption": st["caption"], "visibility": st["visibility"]}, token=tok)
        ledger.stories.append({"id": r["id"], "owner": st["owner"], "file": st["file"], "at": st["at"]})
        ledger.save()
    log(f"stories: {len(ledger.stories)}")
    ledger.phase_done["stories"] = True
    ledger.save()


# --------------------------------------------------------------------------------------------- admin

def _env(name):
    if os.environ.get(name):
        return os.environ[name]
    env = REPO / ".env"
    if env.exists():
        for line in env.read_text().splitlines():
            if line.startswith(name + "="):
                return line.split("=", 1)[1].strip().strip('"').strip("'")
    raise SystemExit(f"{name} not set (env or .env)")


def _otp_from_log():
    path = os.environ.get("SEED_AUTH_LOG")
    if not path or not Path(path).exists():
        return None
    import re
    lines = [l for l in Path(path).read_text(errors="replace").splitlines() if "admin login OTP" in l]
    m = re.search(r"= (\d{6})", lines[-1]) if lines else None
    return m.group(1) if m else None


def admin_login(ledger):
    email, password = _env("ADMIN_EMAIL"), _env("ADMIN_PASSWORD")
    if ledger.admin.get("device_token"):
        try:
            t = auth.call("POST", "/api/v1/auth/login", {"usernameOrEmail": email, "password": password,
                                                        "turnstileToken": TURNSTILE, "deviceToken": ledger.admin["device_token"]})
            ledger.admin["token"] = t["accessToken"]
            ledger.save()
            return t["accessToken"]
        except ApiError as e:
            if e.code != "MFA_REQUIRED":
                raise
    try:
        auth.call("POST", "/api/v1/auth/login", {"usernameOrEmail": email, "password": password, "turnstileToken": TURNSTILE})
        raise RuntimeError("admin login did not ask for MFA; is this account ADMIN?")
    except ApiError as e:
        if e.code != "MFA_REQUIRED":
            raise
    time.sleep(2)
    otp = _otp_from_log() or input("Admin OTP from auth-service log: ").strip()
    t = auth.call("POST", "/api/v1/auth/login/otp", {"usernameOrEmail": email, "otp": otp, "turnstileToken": TURNSTILE, "rememberDevice": True})
    ledger.admin["token"], ledger.admin["device_token"] = t["accessToken"], t.get("deviceToken")
    ledger.save()
    return t["accessToken"]


def early_restore(plan, ledger):
    """After the pipeline: keep ≤ 6 videos in the review queue, restore the rest so engagement can target them."""
    held = [k for k, v in ledger.videos.items() if v["status"] == "PENDING_REVIEW"]
    if not held:
        return
    keep = [k for k in held if ledger.videos[k]["held"]]
    extra = sorted((k for k in held if k not in keep), key=lambda k: ledger.videos[k]["views"])
    keep += extra[:max(0, 6 - len(keep))]
    to_restore = [k for k in held if k not in keep]
    log(f"review queue: {len(held)} held by the pipeline, keeping {len(keep)}, restoring {len(to_restore)}")
    if not to_restore:
        return
    tok = admin_login(ledger)
    restores = [i for i, a in enumerate(plan["admin"]) if a["kind"] == "restore"]
    for n, k in enumerate(to_restore):
        rec = ledger.videos[k]
        r = admin_api.call("POST", f"/api/v1/admin/videos/{rec['id']}/restore", {"reason": "Reviewed: nothing wrong"}, token=tok)
        for _ in range(30):
            v = video_api.call("GET", f"/api/v1/videos/{rec['id']}", token=token_of(ledger, rec["owner"]))
            if v["status"] == "PUBLISHED":
                break
            time.sleep(1)
        rec["status"] = "PUBLISHED"
        idx = restores[n] if n < len(restores) else None
        at = plan["admin"][idx]["at"] if idx is not None else scenario.iso(scenario.parse(rec["at"]) + scenario.DAY)
        if idx is not None:
            ledger.actions[str(idx)] = {"id": r["id"], "at": at}
        else:
            ledger.actions[f"restore-{k}"] = {"id": r["id"], "at": at}
        ledger.save()


def _target_id(a, ledger):
    if a["target_type"] == "USER":
        return str(uid_of(ledger, a["target"]))
    if a["target_type"] == "VIDEO":
        return ledger.videos[a["target"]]["id"]
    c = ledger.comments.get(a["target"])
    return f"{ledger.videos[c['video']]['id']}:{c['id']}" if c else None


def phase_admin(plan, ledger):
    tok = admin_login(ledger)
    for idx, a in enumerate(plan["admin"]):
        key = str(idx)
        if a["kind"] == "report":
            if key not in ledger.reports:
                target = _target_id(a, ledger)
                if target is None or (a["target_type"] == "VIDEO" and ledger.videos[a["target"]]["status"] != "PUBLISHED"):
                    ledger.reports[key] = None
                    continue
                r = admin_api.call("POST", "/api/v1/admin/reports",
                                   {"targetType": a["target_type"], "targetId": target, "reason": a["reason"]},
                                   token=token_of(ledger, a["reporter"]))
                ledger.reports[key] = {"id": r["id"], "at": a["at"], "status": r["status"]}
                ledger.save()
            rep = ledger.reports[key]
            if rep and a["outcome"] != "pending" and rep.get("status") == "PENDING" and key not in ledger.actions:
                body = {"actionType": a["outcome"], "reason": a["reason"]}
                if a.get("ban_days"):
                    body["banDays"] = a["ban_days"]
                r = admin_api.call("POST", f"/api/v1/admin/reports/{rep['id']}/resolve", body, token=tok)
                rep["status"] = r["status"]
                ledger.actions[key] = {"report_id": rep["id"], "at": a["resolved_at"], "type": a["outcome"]}
                if a["outcome"] == "TAKEDOWN_VIDEO":
                    ledger.videos[a["target"]]["status"] = "TAKEN_DOWN"
                ledger.save()
        elif a["kind"] == "remove_comment":
            if key in ledger.actions:
                continue
            c = ledger.comments.get(a["cid"])
            if not c:
                ledger.actions[key] = None
                continue
            r = admin_api.call("POST", f"/api/v1/admin/comments/{ledger.videos[c['video']]['id']}/{c['id']}/remove",
                               {"reason": a["reason"]}, token=tok)
            ledger.actions[key] = {"id": r["id"], "at": a["at"], "type": "REMOVE_COMMENT"}
            ledger.save()
        # "restore" entries are bound in early_restore
    log(f"admin: {sum(1 for v in ledger.reports.values() if v)} reports, {sum(1 for v in ledger.actions.values() if v)} actions")
    ledger.phase_done["admin"] = True
    ledger.save()


# --------------------------------------------------------------------------------------------- refresh

def phase_refresh(plan, ledger):
    """Fresh engagement on the top videos so reco:trending is not empty on demo day (24 h decay)."""
    import random
    rnd = random.Random()
    import content as C
    banned = {p["username"] for p in C.PERSONAS if p["spam"] or p["temp_ban"]}
    users = [u for u in ledger.users if u not in banned]
    top = sorted((k for k, v in ledger.videos.items() if v["status"] == "PUBLISHED"), key=lambda k: -ledger.videos[k]["views"])[:30]
    liked = {(x["actor"], x["video"]) for x in ledger.likes} | {(x["actor"], x["video"]) for x in ledger.refresh if x.get("kind") == "like"}
    used = {(c["video"], c.get("text")) for c in ledger.comments.values()}
    now = scenario.iso(scenario.datetime.now(scenario.timezone.utc))
    sent = {"like": 0, "comment": 0, "view": 0}
    for key in top:
        rec = ledger.videos[key]
        owner, vid, dur = rec["owner"], rec["id"], max(1, rec["duration"]) * 1000
        base = f"/api/v1/interactions/videos/{vid}"
        cands = [u for u in users if u != owner and (u, key) not in liked]
        for u in rnd.sample(cands, min(10, len(cands))):
            try:
                inter.call("POST", base + "/like", token=token_of(ledger, u))
                ledger.refresh.append({"kind": "like", "actor": u, "video": key, "at": now})
                ledger.likes.append({"actor": u, "video": key, "at": now})
                sent["like"] += 1
            except ApiError as e:
                log(f"refresh like skipped: {e}")
        texts = [t for t in C.COMMENTS if (key, t) not in used]
        for u, text in zip(rnd.sample([x for x in users if x != owner], 3), rnd.sample(texts, 3)):
            try:
                r = inter.call("POST", base + "/comments", {"content": text}, token=token_of(ledger, u))
                ledger.comments[f"r{r['commentId']}"] = {"id": r["commentId"], "video": key, "at": now, "author": u, "text": text,
                                                         "parent_cid": None, "status": r.get("status") or "VISIBLE"}
                sent["comment"] += 1
                time.sleep(0.3)
            except ApiError as e:
                _handle_comment_error(e, u, set()) if e.code in ("COMMENT_REJECTED", "DUPLICATE_COMMENT", "COMMENTING_RESTRICTED") else log(f"refresh comment skipped: {e}")
        for u in rnd.sample([x for x in users if x != owner], 10):
            tok = token_of(ledger, u)
            try:
                inter.call("POST", base + "/view", {"playId": uuid.uuid4().hex[:32]}, token=tok)
                inter.call("POST", base + "/watch", {"watchedMs": int(dur * rnd.uniform(0.4, 1.0)), "durationMs": dur}, token=tok)
                sent["view"] += 1
            except ApiError as e:
                log(f"refresh view skipped: {e}")
        ledger.save()
    log(f"refresh: {sent}")


# --------------------------------------------------------------------------------------------- main

PHASES = ["users", "videos", "engagement", "chat", "admin", "stories"]


def selftest():
    muted = set()
    _handle_comment_error(ApiError(403, "COMMENTING_RESTRICTED", ""), "u", muted)
    assert "u" in muted
    _handle_comment_error(ApiError(422, "COMMENT_REJECTED", ""), "v", muted)
    assert muted == {"u"}
    try:
        _handle_comment_error(ApiError(500, "X", ""), "w", muted)
        raise AssertionError("500 must raise")
    except ApiError:
        pass
    print("ok")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("media_dir", nargs="?", default="~/Projects/EProject/EProject4/seed-media")
    ap.add_argument("--seed", type=int, default=2026)
    ap.add_argument("--phase", default="all")
    ap.add_argument("--refresh", action="store_true")
    ap.add_argument("--selftest", action="store_true")
    args = ap.parse_args()
    if args.selftest:
        return selftest()
    if args.refresh:
        args.phase = "refresh"
    media_dir = Path(args.media_dir).expanduser()
    manifest = json.loads((media_dir / "manifest.json").read_text())
    ledger = Ledger(media_dir / "ledger.json")
    if "t1" not in ledger.admin:
        ledger.admin["t1"] = scenario.iso(scenario.datetime.now(scenario.timezone.utc))
        ledger.save()
    plan = scenario.build(manifest, seed=args.seed, now=scenario.parse(ledger.admin["t1"]))
    phases = PHASES if args.phase == "all" else [args.phase]
    g = globals()
    for ph in phases:
        fn = g.get("phase_" + ph)
        if fn is None:
            raise SystemExit(f"phase {ph} not implemented yet")
        log(f"== phase {ph}")
        fn(plan, ledger, media_dir) if ph in ("users", "videos", "stories") else fn(plan, ledger)
        if ph == "videos":
            early_restore(plan, ledger)
    if args.phase == "all":
        log("create pass done — now run: make seed-backdate")


if __name__ == "__main__":
    main()
