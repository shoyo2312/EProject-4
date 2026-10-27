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


# --------------------------------------------------------------------------------------------- main

PHASES = ["users", "videos", "engagement", "chat", "admin", "stories"]


def selftest():
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
    if args.phase == "all":
        log("create pass done — now run: make seed-backdate")


if __name__ == "__main__":
    main()
