#!/usr/bin/env python3
"""Second pass of the demo seed: rewrite timestamps and counters in every store from ledger.json.

    python3 -I backdate.py <media-dir> [--seed 2026] [--only auth,user,...]

Idempotent. Run only after seed.py finished (every video out of PROCESSING / PENDING_MODERATION).
Writes go through the CLIs inside the containers (psql, mongosh, cqlsh, clickhouse-client, redis-cli)
and Elasticsearch over HTTP; nothing to install.
"""
import argparse
import json
import random
import subprocess
import sys
import urllib.request
from collections import defaultdict
from datetime import timedelta
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import scenario  # noqa: E402
from seed import Ledger, log  # noqa: E402

iso, parse = scenario.iso, scenario.parse


# --------------------------------------------------------------------------------------------- CLI helpers

def _exec(args, stdin):
    r = subprocess.run(["docker", "exec", "-i", *args], input=stdin.encode(), capture_output=True, check=False)
    if r.returncode != 0:
        raise RuntimeError(f"{args[0]} {args[1]}: {r.stderr.decode()[-2000:]}")
    return r.stdout.decode()


def psql(container, db, script):
    return _exec([container, "psql", "-U", "postgres", "-d", db, "-v", "ON_ERROR_STOP=1", "-tAq"], script)


def mongosh(db, js):
    return _exec(["tt-mongodb", "mongosh", "-u", "root", "-p", "password", "--authenticationDatabase", "admin", "--quiet", db], js)


def cqlsh(cql):
    return _exec(["tt-cassandra", "cqlsh", "-k", "interaction_ks"], cql)


def cql_copy(table, columns):
    """COPY ... TO STDOUT → list of rows (strings)."""
    out = _exec(["tt-cassandra", "cqlsh", "-k", "interaction_ks", "-e", f"COPY {table} ({', '.join(columns)}) TO STDOUT;"], "")
    return [line.split(",") for line in out.splitlines() if line and line[0].isdigit()]


def clickhouse(query, stdin=""):
    return _exec(["tt-clickhouse", "clickhouse-client", "--password", "clickhouse", "-d", "analytics_db", "--query", query], stdin)


def redis(lines):
    return _exec(["tt-redis", "redis-cli"], "\n".join(lines) + "\n")


def es_bulk(ndjson):
    req = urllib.request.Request("http://localhost:9200/_bulk", data=ndjson.encode(), method="POST",
                                 headers={"Content-Type": "application/x-ndjson"})
    with urllib.request.urlopen(req, timeout=300) as r:
        j = json.loads(r.read())
    bad = [i for i in j.get("items", []) if i["update"].get("status", 200) >= 300 and i["update"].get("status") != 404]
    if bad:
        raise RuntimeError(f"ES bulk errors: {bad[:3]}")
    return j


# literal formats
def pg_ts(s):
    return f"'{s}'::timestamptz"


def mongo_ts(s):
    return f'new Date("{s}")'


def cql_ts(s):
    return "'" + s.replace("Z", "+0000") + "'"


def ch_ts(s):
    return f"toDateTime64('{s[:10]} {s[11:23]}', 3, 'UTC')"


def es_ts(s):
    return s[:23]


def shift(s, seconds):
    return iso(parse(s) + timedelta(seconds=seconds))


def epoch(s):
    return int(parse(s).timestamp())


# --------------------------------------------------------------------------------------------- stores

def backdate_auth(plan, ledger):
    lines = []
    for u in plan["users"]:
        rec = ledger.users[u["username"]]
        t = pg_ts(u["join_at"])
        lines.append(f"UPDATE users SET created_at={t}, updated_at={t}, email_verified_at={t}, last_login_at={t} WHERE id={rec['id']};")
        lines.append(f"UPDATE auth_security_events SET created_at={t} WHERE user_id={rec['id']};")
    for a in plan["admin"]:
        if a["kind"] == "report" and a["outcome"] == "BAN_USER":
            lines.append(f"UPDATE users SET banned_at={pg_ts(a['resolved_at'])} WHERE id={ledger.users[a['target']]['id']} AND status='BANNED';")
    psql("tt-postgres-auth", "auth_db", "\n".join(lines))
    log(f"auth: {len(plan['users'])} users")


def backdate_user(plan, ledger):
    lines = []
    for u in plan["users"]:
        t = pg_ts(u["join_at"])
        lines.append(f"UPDATE user_profiles SET created_at={t}, updated_at={t} WHERE user_id={ledger.users[u['username']]['id']};")
    for f in ledger.follows:
        t = pg_ts(f["at"])
        a, b = ledger.users[f["follower"]]["id"], ledger.users[f["following"]]["id"]
        if f.get("status") == "REQUESTED":
            lines.append(f"UPDATE user_follow_requests SET created_at={t}, updated_at={t} WHERE requester_id={a} AND target_id={b};")
        else:
            lines.append(f"UPDATE user_follows SET created_at={t}, updated_at={t} WHERE follower_id={a} AND following_id={b};")
    psql("tt-postgres-user", "user_db", "\n".join(lines))
    log(f"user: {len(ledger.follows)} follows")


def _finished(rec):
    return rec["status"] not in ("PROCESSING", "PENDING_MODERATION")


def backdate_video(ledger):
    ops = []
    for key, rec in ledger.videos.items():
        if not _finished(rec):
            continue
        at = rec["at"]
        sets = {"createdAt": mongo_ts(at), "updatedAt": mongo_ts(shift(at, 90)), "viewCount": rec["views"], "likeCount": rec["likes"]}
        if rec["status"] != "FAILED":
            sets["publishedAt"] = mongo_ts(shift(at, 90))
            sets['"moderation.checkedAt"'] = mongo_ts(shift(at, 60))
            sets['"textModeration.checkedAt"'] = mongo_ts(at)
        body = ", ".join(f"{k}: {v}" for k, v in sets.items())
        ops.append(f'{{updateOne: {{filter: {{_id: "{rec["id"]}"}}, update: {{$set: {{{body}}}}}}}}}')
    mongosh("video_db", f"db.videos.bulkWrite([{','.join(ops)}], {{ordered: false}});")
    log(f"video: {len(ops)} documents")


def backdate_es(ledger):
    lines = []
    counts = defaultdict(lambda: {"commentCount": 0, "shareCount": 0})
    for c in ledger.comments.values():
        if c.get("status", "VISIBLE") == "VISIBLE":
            counts[c["video"]]["commentCount"] += 1
    for s in ledger.shares:
        counts[s["video"]]["shareCount"] += 1
    for key, rec in ledger.videos.items():
        if rec["status"] != "PUBLISHED":
            continue
        doc = {"createdAt": es_ts(rec["at"]), "viewCount": rec["views"], "likeCount": rec["likes"], **counts[key]}
        lines.append(json.dumps({"update": {"_index": "videos", "_id": rec["id"]}}))
        lines.append(json.dumps({"doc": doc}))
    if lines:
        es_bulk("\n".join(lines) + "\n")
    log(f"es: {len(lines) // 2} documents")


def backdate_reco(ledger):
    lines = []
    for rec in ledger.videos.values():
        if rec["status"] != "PUBLISHED":
            continue
        e, vid = epoch(rec["at"]), rec["id"]
        lines.append(f"ZADD reco:video:published XX {e} {vid}")
        for tag in rec["tags"]:
            lines.append(f"ZADD reco:tag:{tag} XX {e} {vid}")
        lines.append(f"ZADD reco:video:watches {rec['views']} {vid}")
        lines.append(f"ZADD reco:video:completions {rec['completions']} {vid}")
    redis(lines)
    log(f"reco: {len(lines)} commands")


def _cql_old_ts(table, cols):
    """(first col, second col) -> created_at as COPY printed it."""
    return {(row[0], row[1]): row[2] for row in cql_copy(table, cols) if len(row) == 3}


def backdate_cassandra(plan, ledger):
    uid = {u: r["id"] for u, r in ledger.users.items()}
    vid = {k: r["id"] for k, r in ledger.videos.items()}
    lines = []
    for cid, c in ledger.comments.items():
        lines.append(f"UPDATE comments_by_video SET created_at={cql_ts(c['at'])} WHERE video_id={vid[c['video']]} AND comment_id={c['id']};")
    for s in ledger.shares:
        lines.append(f"UPDATE shares_by_video SET created_at={cql_ts(s['at'])} WHERE video_id={vid[s['video']]} AND share_id={s['id']};")
    for cl in ledger.comment_likes:
        c = ledger.comments.get(cl["cid"])
        if c:
            lines.append(f"UPDATE comment_likes SET created_at={cql_ts(cl['at'])} WHERE comment_id={c['id']} AND user_id={uid[cl['actor']]};")
    pairs = [
        ("likes", "likes_by_video", ("video_id", "user_id", "created_at"), "likes_by_user"),
        ("reposts", "reposts_by_video", ("video_id", "user_id", "created_at"), "reposts_by_user"),
        ("saves", "saves_by_user", ("user_id", "video_id", "created_at"), "saves_by_user_time"),
    ]
    moved = 0
    for section, member, cols, listing in pairs:
        old = _cql_old_ts(member, cols)
        for x in getattr(ledger, section):
            v, u = str(vid[x["video"]]), str(uid[x["actor"]])
            k = (v, u) if cols[0] == "video_id" else (u, v)
            if k not in old:
                continue
            new = cql_ts(x["at"])
            if old[k].strip()[:19].replace(" ", "T") == x["at"][:19]:
                continue
            lines.append(f"DELETE FROM {listing} WHERE user_id={u} AND created_at='{old[k].strip()}' AND video_id={v};")
            lines.append(f"INSERT INTO {listing} (user_id, created_at, video_id) VALUES ({u}, {new}, {v});")
            where = f"video_id={v} AND user_id={u}" if cols[0] == "video_id" else f"user_id={u} AND video_id={v}"
            lines.append(f"UPDATE {member} SET created_at={new} WHERE {where};")
            moved += 1
    if not ledger.admin.get("counters_applied"):
        api_likes = defaultdict(int)
        for x in ledger.likes:
            api_likes[x["video"]] += 1
        for key, rec in ledger.videos.items():
            if rec["status"] != "PUBLISHED":
                continue
            extra = max(0, rec["likes"] - api_likes[key])
            lines.append(f"UPDATE video_counters SET view_count = view_count + {rec['views']}, like_count = like_count + {extra} WHERE video_id={rec['id']};")
    for i in range(0, len(lines), 2000):
        cqlsh("\n".join(lines[i:i + 2000]))
    if not ledger.admin.get("counters_applied"):
        ledger.admin["counters_applied"] = True
        ledger.save()
    log(f"cassandra: {len(lines)} statements, {moved} listing rows moved")


def backdate_chat(ledger):
    msgs, convs = [], []
    for c in ledger.chats:
        if not c["messages"]:
            continue
        for m in c["messages"]:
            msgs.append(f'{{updateOne: {{filter: {{_id: "{m["id"]}"}}, update: {{$set: {{sentAt: {mongo_ts(m["at"])}}}}}}}}}')
        first, last = c["messages"][0], c["messages"][-1]
        other = c["b"] if last["from"] == c["a"] else c["a"]
        last_other = [m for m in c["messages"] if m["from"] == other]
        sets = {"createdAt": mongo_ts(first["at"]), "lastMessageAt": mongo_ts(last["at"]),
                f'"lastReadAt.{ledger.users[last["from"]]["id"]}"': mongo_ts(last["at"])}
        if last_other:
            sets[f'"lastReadAt.{ledger.users[other]["id"]}"'] = mongo_ts(last_other[-1]["at"])
        body = ", ".join(f"{k}: {v}" for k, v in sets.items())
        convs.append(f'{{updateOne: {{filter: {{_id: "{c["id"]}"}}, update: {{$set: {{{body}}}}}}}}}')
    if msgs:
        mongosh("chat_db", f"db.messages.bulkWrite([{','.join(msgs)}], {{ordered: false}});")
    if convs:
        mongosh("chat_db", f"db.conversations.bulkWrite([{','.join(convs)}], {{ordered: false}});")
    log(f"chat: {len(msgs)} messages, {len(convs)} conversations")


def backdate_notifications(plan, ledger):
    uid = {u: r["id"] for u, r in ledger.users.items()}
    owner = {k: r["owner"] for k, r in ledger.videos.items()}
    vid = {k: r["id"] for k, r in ledger.videos.items()}
    earliest = {}

    def put(recipient, actor, typ, ref, at):
        if recipient == actor:
            return
        k = (uid[recipient], uid[actor], typ, str(ref))
        if k not in earliest or at < earliest[k]:
            earliest[k] = at

    for x in ledger.likes:
        put(owner[x["video"]], x["actor"], "LIKE", vid[x["video"]], x["at"])
    for x in ledger.shares:
        put(owner[x["video"]], x["actor"], "SHARE", vid[x["video"]], x["at"])
    for x in ledger.reposts:
        put(owner[x["video"]], x["actor"], "REPOST", vid[x["video"]], x["at"])
    for cid, c in ledger.comments.items():
        parent = ledger.comments.get(c.get("parent_cid") or "")
        recipient = parent["author"] if parent else owner[c["video"]]
        put(recipient, c["author"], "COMMENT", vid[c["video"]], c["at"])
    for x in ledger.comment_likes:
        c = ledger.comments.get(x["cid"])
        if c:
            put(c["author"], x["actor"], "COMMENT_LIKE", vid[c["video"]], x["at"])
    for f in ledger.follows:
        typ = "FOLLOW_REQUEST" if f.get("status") == "REQUESTED" else "NEW_FOLLOWER"
        put(f["following"], f["follower"], typ, uid[f["follower"]], f["at"])
    ops = [f'{{updateOne: {{filter: {{recipientId: {r}, actorId: {a}, type: "{t}", referenceId: "{ref}"}}, '
           f'update: {{$set: {{createdAt: {mongo_ts(at)}}}}}}}}}' for (r, a, t, ref), at in earliest.items()]
    for u in plan["users"]:
        ops.append(f'{{updateMany: {{filter: {{recipientId: {uid[u["username"]]}, type: "SYSTEM", actorId: null, referenceId: null}}, '
                   f'update: {{$set: {{createdAt: {mongo_ts(u["join_at"])}}}}}}}}}')
    for a in plan["admin"]:
        if a["kind"] != "report" or a["outcome"] in ("pending", "DISMISS_REPORT"):
            continue
        if a["target_type"] == "USER":
            recipient = a["target"]
        elif a["target_type"] == "VIDEO":
            recipient = owner[a["target"]]
        else:
            c = ledger.comments.get(a["target"])
            recipient = c["author"] if c else None
        if recipient:
            ops.append(f'{{updateMany: {{filter: {{recipientId: {uid[recipient]}, type: "SYSTEM", actorId: null, referenceId: {{$ne: null}}}}, '
                       f'update: {{$set: {{createdAt: {mongo_ts(a["resolved_at"])}}}}}}}}}')
    for i in range(0, len(ops), 2000):
        mongosh("notification_db", f"db.notifications.bulkWrite([{','.join(ops[i:i + 2000])}], {{ordered: false}});")
    log(f"notifications: {len(ops)} updates")


def backdate_admin(plan, ledger):
    lines = []
    for idx, a in enumerate(plan["admin"]):
        key = str(idx)
        rep = ledger.reports.get(key)
        if a["kind"] == "report" and rep:
            t = pg_ts(a["at"])
            if a["outcome"] == "pending":
                lines.append(f"UPDATE reports SET created_at={t}, updated_at={t} WHERE id={rep['id']};")
            else:
                r = pg_ts(a["resolved_at"])
                lines.append(f"UPDATE reports SET created_at={t}, updated_at={r}, resolved_at={r} WHERE id={rep['id']} AND status <> 'PENDING';")
                lines.append(f"UPDATE moderation_actions SET created_at={r}, updated_at={r} WHERE report_id={rep['id']};")
        act = ledger.actions.get(key)
        if act and act.get("id"):
            t = pg_ts(act["at"])
            lines.append(f"UPDATE moderation_actions SET created_at={t}, updated_at={t} WHERE id={act['id']};")
    for key, act in ledger.actions.items():
        if key.startswith("restore-") and act:
            t = pg_ts(act["at"])
            lines.append(f"UPDATE moderation_actions SET created_at={t}, updated_at={t} WHERE id={act['id']};")
    if lines:
        psql("tt-postgres-admin", "admin_db", "\n".join(lines))
    log(f"admin: {len(lines)} statements")


def backdate_clickhouse(plan, ledger):
    uid = {u: r["id"] for u, r in ledger.users.items()}
    vid = {k: r["id"] for k, r in ledger.videos.items()}
    rows = []
    for k, rec in ledger.videos.items():
        if _finished(rec) and rec["status"] != "FAILED":
            rows.append(("PUBLISHED", vid[k], uid[rec["owner"]], rec["at"]))
    for x in ledger.likes:
        rows.append(("LIKED", vid[x["video"]], uid[x["actor"]], x["at"]))
    for c in ledger.comments.values():
        rows.append(("COMMENTED", vid[c["video"]], uid[c["author"]], c["at"]))
    for x in ledger.shares:
        rows.append(("SHARED", vid[x["video"]], uid[x["actor"]], x["at"]))
    first = {}
    for et, v, u, at in rows:
        if (et, v, u) not in first or at < first[(et, v, u)]:
            first[(et, v, u)] = at
    items = sorted(first.items())
    for i in range(0, len(items), 500):
        chunk = items[i:i + 500]
        conds = ", ".join(f"event_type='{et}' AND video_id='{v}' AND user_id={u}, {ch_ts(at)}" for (et, v, u), at in chunk)
        where = " OR ".join(f"(video_id='{v}' AND user_id={u})" for (et, v, u), _ in chunk)
        clickhouse(f"ALTER TABLE engagement_events UPDATE occurred_at = multiIf({conds}, occurred_at) WHERE {where}")
    tag_conds = ", ".join(f"video_id='{r['id']}', {ch_ts(r['at'])}" for r in ledger.videos.values() if _finished(r))
    clickhouse(f"ALTER TABLE video_tags UPDATE published_at = multiIf({tag_conds}, published_at) WHERE 1")
    su = ", ".join(f"user_id={uid[u['username']]}, {ch_ts(u['join_at'])}" for u in plan["users"])
    clickhouse(f"ALTER TABLE user_signup_events UPDATE occurred_at = multiIf({su}, occurred_at) WHERE 1")
    n = int(clickhouse("SELECT count() FROM watch_events WHERE event_id LIKE 'seed-%'").strip() or 0)
    inserted = 0
    if n == 0:
        rnd = random.Random(7)
        t1 = parse(plan["t1"])
        users = [uid[u["username"]] for u in plan["users"]]
        buf = []
        for k, rec in ledger.videos.items():
            if rec["status"] != "PUBLISHED":
                continue
            dur = max(1, rec["duration"]) * 1000
            for i, at in enumerate(scenario._burst_times(rnd, parse(rec["at"]), t1, rec["views"])):
                completed = i < rec["completions"]
                watched = dur if completed else int(dur * rnd.uniform(0.3, 0.89))
                buf.append(json.dumps({"event_id": f"seed-{rec['id']}-{i}", "video_id": rec["id"], "user_id": rnd.choice(users),
                                       "watched_ms": watched, "duration_ms": dur, "completed": int(completed),
                                       "occurred_at": iso(at)[:23].replace("T", " ")}))
            if len(buf) >= 100000:
                clickhouse("INSERT INTO watch_events FORMAT JSONEachRow", "\n".join(buf))
                inserted += len(buf)
                buf = []
        if buf:
            clickhouse("INSERT INTO watch_events FORMAT JSONEachRow", "\n".join(buf))
            inserted += len(buf)
    log(f"clickhouse: {len(items)} engagement rows, {inserted} watch rows inserted ({n} existed)")


def flush_redis_caches(ledger):
    keys = []
    for rec in ledger.videos.values():
        keys += [f"video:{rec['id']}", f"interaction:counters:{rec['id']}"]
    redis([f"DEL {' '.join(keys[i:i + 500])}" for i in range(0, len(keys), 500)])
    log(f"redis: {len(keys)} cache keys dropped")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("media_dir", nargs="?", default="~/Projects/EProject/EProject4/seed-media")
    ap.add_argument("--seed", type=int, default=2026)
    ap.add_argument("--only", help="comma-separated stores: auth,user,video,es,reco,cassandra,chat,notifications,admin,clickhouse,redis")
    args = ap.parse_args()
    media_dir = Path(args.media_dir).expanduser()
    manifest = json.loads((media_dir / "manifest.json").read_text())
    ledger = Ledger(media_dir / "ledger.json")
    plan = scenario.build(manifest, seed=args.seed, now=parse(ledger.admin["t1"]))
    waiting = [k for k, r in ledger.videos.items() if not _finished(r)]
    if waiting:
        raise SystemExit(f"{len(waiting)} videos still in the pipeline; run seed.py --phase videos first")
    steps = [
        ("auth", lambda: backdate_auth(plan, ledger)),
        ("user", lambda: backdate_user(plan, ledger)),
        ("video", lambda: backdate_video(ledger)),
        ("es", lambda: backdate_es(ledger)),
        ("reco", lambda: backdate_reco(ledger)),
        ("cassandra", lambda: backdate_cassandra(plan, ledger)),
        ("chat", lambda: backdate_chat(ledger)),
        ("notifications", lambda: backdate_notifications(plan, ledger)),
        ("admin", lambda: backdate_admin(plan, ledger)),
        ("clickhouse", lambda: backdate_clickhouse(plan, ledger)),
        ("redis", lambda: flush_redis_caches(ledger)),
    ]
    only = set(args.only.split(",")) if args.only else None
    for name, fn in steps:
        if only is None or name in only:
            fn()
    log("backdate done")


if __name__ == "__main__":
    main()
