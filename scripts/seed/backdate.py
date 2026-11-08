#!/usr/bin/env python3
"""Second pass of the demo seed: rewrite timestamps and counters in every store from ledger.json.

    python3 -I backdate.py <media-dir> [--seed 2026] [--only auth,user,...] [--grow 8 --months 12]

Idempotent. `--grow` re-times the seed as steady growth before writing (see `regrow`); the ledger
file keeps the planned times, so running without it puts them back. Run only after seed.py finished (every video out of PROCESSING / PENDING_MODERATION).
Writes go through the CLIs inside the containers (psql, mongosh, cqlsh, clickhouse-client, redis-cli)
and Elasticsearch over HTTP; nothing to install.
"""
import argparse
import bisect
import json
import math
import random
import subprocess
import sys
import urllib.request
from collections import defaultdict
from datetime import datetime, timedelta, timezone
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


def mongo_long(n):
    return f'NumberLong("{n}")'


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

def last_acts(ledger):
    """Each user's latest planned act: when they were last signed in."""
    last = {}

    def see(who, at):
        if who and at > last.get(who, ""):
            last[who] = at
    for section in ("likes", "shares", "reposts", "saves", "comment_likes", "follows", "blocks", "mutes"):
        for x in getattr(ledger, section):
            see(x.get("actor") or x.get("follower") or x.get("blocker") or x.get("muter"), x["at"])
    for c in ledger.comments.values():
        see(c["author"], c["at"])
    for c in ledger.chats:
        for m in c["messages"]:
            see(m["from"], m["at"])
    for v in ledger.videos.values():
        see(v["owner"], v["at"])
    return last


def backdate_auth(plan, ledger):
    import realism
    lines = []
    last = last_acts(ledger)
    for u in plan["users"]:
        rec = ledger.users[u["username"]]
        t = pg_ts(u["join_at"])
        seen = max(last.get(u["username"], u["join_at"]), u["join_at"])
        lines.append(f"UPDATE users SET created_at={t}, updated_at={t}, email_verified_at={t}, last_login_at={pg_ts(seen)} WHERE id={rec['id']};")
        # sign-ins kept in order between joining and the last thing they did, not all on the day they joined
        lines.append(realism.spread_events(rec["id"], u["join_at"], seen))
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
    for b in ledger.blocks:
        t = pg_ts(b["at"])
        lines.append(f"UPDATE user_blocks SET created_at={t}, updated_at={t} WHERE blocker_id={ledger.users[b['blocker']]['id']} AND blocked_id={ledger.users[b['blocked']]['id']};")
    for m in ledger.mutes:
        t = pg_ts(m["at"])
        lines.append(f"UPDATE user_mutes SET created_at={t}, updated_at={t} WHERE muter_id={ledger.users[m['muter']]['id']} AND muted_id={ledger.users[m['muted']]['id']};")
    psql("tt-postgres-user", "user_db", "\n".join(lines))
    log(f"user: {len(ledger.follows)} follows, {len(ledger.blocks)} blocks, {len(ledger.mutes)} mutes")


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
    t1 = ledger.admin["t1"]
    for section, member, cols, listing in pairs:
        old = _cql_old_ts(member, cols)
        for x in getattr(ledger, section):
            if x["at"] > t1:  # sent by --refresh: real time is the right time
                continue
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
    for i in range(0, len(lines), 2000):
        cqlsh("\n".join(lines[i:i + 2000]))
    if not ledger.admin.get("counters_applied"):  # counters only add; flag first so a crash never doubles them
        api_likes = defaultdict(int)
        for x in ledger.likes:
            api_likes[x["video"]] += 1
        counters = [f"UPDATE video_counters SET view_count = view_count + {rec['views']}, like_count = like_count + {max(0, rec['likes'] - api_likes[key])} WHERE video_id={rec['id']};"
                    for key, rec in ledger.videos.items() if rec["status"] in ("PUBLISHED", "DELETED")]  # a deleted one keeps its counts in the trash
        # Saved from a fresh read: the ledger in hand may carry `regrow`'s times, which must not
        # reach the file, or a second --grow would warp them again.
        fresh = Ledger(ledger.path)
        fresh.admin["counters_applied"] = ledger.admin["counters_applied"] = True
        fresh.save()
        cqlsh("\n".join(counters))
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
    earliest, comment_dates = {}, defaultdict(list)

    def put(recipient, actor, typ, ref, at):
        if recipient == actor:
            return
        k = (uid[recipient], uid[actor], typ, str(ref))
        if typ == "COMMENT":  # COMMENT is not collapsed by the service: one document per comment
            comment_dates[k].append(at)
        elif k not in earliest or at < earliest[k]:
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
    ops = [f'{{updateOne: {{filter: {{recipientId: {mongo_long(r)}, actorId: {mongo_long(a)}, type: "{t}", referenceId: "{ref}"}}, '
           f'update: {{$set: {{createdAt: {mongo_ts(at)}}}}}}}}}' for (r, a, t, ref), at in earliest.items()]
    for u in plan["users"]:
        ops.append(f'{{updateMany: {{filter: {{recipientId: {mongo_long(uid[u["username"]])}, type: "SYSTEM", actorId: null, referenceId: null}}, '
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
            ops.append(f'{{updateMany: {{filter: {{recipientId: {mongo_long(uid[recipient])}, type: "SYSTEM", actorId: null, referenceId: {{$ne: null}}}}, '
                       f'update: {{$set: {{createdAt: {mongo_ts(a["resolved_at"])}}}}}}}}}')
    # COMMENT: documents in _id order get the planned instants in time order (one per comment, nothing collapsed)
    groups = [[str(r), str(a), ref, sorted(dates)] for (r, a, t, ref), dates in comment_dates.items()]  # ids as strings: JSON numbers lose precision past 2^53
    js = ("const groups = " + json.dumps(groups) + ";\n"
          "let n = 0; groups.forEach(([r, a, ref, dates]) => {\n"
          "  const docs = db.notifications.find({recipientId: NumberLong(r), actorId: NumberLong(a), type: 'COMMENT', referenceId: ref}).sort({_id: 1}).toArray();\n"
          "  dates.forEach((d, i) => { if (docs[i]) { db.notifications.updateOne({_id: docs[i]._id}, {$set: {createdAt: new Date(d)}}); n++; } });\n"
          "});\nprint('comment notifications: ' + n);")
    log("notifications: " + mongosh("notification_db", js).strip())
    for i in range(0, len(ops), 2000):
        mongosh("notification_db", f"db.notifications.bulkWrite([{','.join(ops[i:i + 2000])}], {{ordered: false}});")
    log(f"notifications: {len(ops)} updates")


def backdate_admin(plan, ledger):
    lines = []
    for a in plan["admin"]:
        key = a["key"]
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
    ids = ledger.admin.get("term_ids", {})
    for t in plan["terms"]:  # each term on the day it was added; the two removed ones a few days later
        if t["term"] in ids:
            gone = f", deleted_at={pg_ts(t['removed_at'])}" if t.get("removed_at") else ""
            lines.append(f"UPDATE moderation_terms SET created_at={pg_ts(t['at'])}, updated_at={pg_ts(t.get('removed_at') or t['at'])}{gone} "
                         f"WHERE id={ids[t['term']]};")
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
    for x in ledger.saves:
        rows.append(("SAVED", vid[x["video"]], uid[x["actor"]], x["at"]))
    for a in plan["admin"]:  # admin comment removals produce UNCOMMENTED rows
        cid = a.get("cid") if a["kind"] == "remove_comment" else a.get("target") if a["kind"] == "report" and a["target_type"] == "COMMENT" and a["outcome"] == "REMOVE_COMMENT" else None
        c = ledger.comments.get(cid or "")
        if c and ledger.actions.get(a["key"]):
            rows.append(("UNCOMMENTED", vid[c["video"]], uid[c["author"]], a.get("resolved_at") or a["at"]))
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
    fo = [f for f in ledger.follows if f.get("status") != "REQUESTED"]
    for i in range(0, len(fo), 500):
        chunk = fo[i:i + 500]
        conds = ", ".join(f"follower_id={uid[f['follower']]} AND following_id={uid[f['following']]}, {ch_ts(f['at'])}" for f in chunk)
        where = " OR ".join(f"(follower_id={uid[f['follower']]} AND following_id={uid[f['following']]})" for f in chunk)
        clickhouse(f"ALTER TABLE follow_events UPDATE occurred_at = multiIf({conds}, occurred_at) WHERE followed = 1 AND ({where})")
    # Rebuilt every run, so the viewers follow whatever join dates this run wrote.
    clickhouse("ALTER TABLE watch_events DELETE WHERE event_id LIKE 'seed-%' SETTINGS mutations_sync = 1")
    inserted = 0
    rnd = random.Random(7)
    t1 = parse(plan["t1"])
    viewers = _daily_viewers(plan, uid)
    buf = []
    for k, rec in ledger.videos.items():
        if rec["status"] != "PUBLISHED":
            continue
        dur = max(1, rec["duration"]) * 1000
        for i, at in enumerate(scenario._burst_times(rnd, parse(rec["at"]), t1, rec["views"])):
            completed = i < rec["completions"]
            watched = dur if completed else int(dur * rnd.uniform(0.3, 0.89))
            buf.append(json.dumps({"event_id": f"seed-{rec['id']}-{i}", "video_id": rec["id"], "user_id": rnd.choice(viewers(at)),
                                   "watched_ms": watched, "duration_ms": dur, "completed": int(completed),
                                   "occurred_at": iso(at)[:23].replace("T", " ")}))
        if len(buf) >= 100000:
            clickhouse("INSERT INTO watch_events FORMAT JSONEachRow", "\n".join(buf))
            inserted += len(buf)
            buf = []
    if buf:
        clickhouse("INSERT INTO watch_events FORMAT JSONEachRow", "\n".join(buf))
        inserted += len(buf)
    # Watch rows written outside the seed (the Studio test clips) under seed users: drawn again from
    # the same daily viewers, or each of their days would count every seed user as active.
    seed_ids = {str(i) for i in uid.values()}
    others = [json.loads(line) for line in clickhouse(
        "SELECT * FROM watch_events WHERE event_id NOT LIKE 'seed-%' ORDER BY event_id FORMAT JSONEachRow").splitlines()]
    others = [r for r in others if r["user_id"] in seed_ids]
    if others:
        ids = ",".join(seed_ids)
        clickhouse(f"ALTER TABLE watch_events DELETE WHERE event_id NOT LIKE 'seed-%' AND user_id IN ({ids}) SETTINGS mutations_sync = 1")
        for r in others:
            at = datetime.strptime(r["occurred_at"], "%Y-%m-%d %H:%M:%S.%f").replace(tzinfo=timezone.utc)
            r["user_id"] = rnd.choice(viewers(at))
        clickhouse("INSERT INTO watch_events FORMAT JSONEachRow", "\n".join(json.dumps(r) for r in others))
    log(f"clickhouse: {len(items)} engagement rows, {len(fo)} follow rows, {inserted} watch rows, {len(others)} other watch rows re-drawn")


def _daily_viewers(plan, uid):
    """at -> the users who watch that day: those who had joined by then, each on about half their
    days (a fixed 30–80 % per user), so daily actives follow the signups instead of counting
    everyone from the first day."""
    joined = sorted((parse(u["join_at"]), uid[u["username"]]) for u in plan["users"])
    habit = {u: random.Random(u).uniform(0.3, 0.8) for _, u in joined}
    cache = {}

    def viewers(at):
        day = at.date()
        if day not in cache:
            members = [u for j, u in joined if j <= at]
            cache[day] = [u for u in members if random.Random(f"{day}-{u}").random() < habit[u]] or members
        return cache[day] or [joined[0][1]]
    return viewers


def flush_redis_caches(ledger):
    keys = []
    for rec in ledger.videos.values():
        keys += [f"video:{rec['id']}", f"interaction:counters:{rec['id']}"]
    redis([f"DEL {' '.join(keys[i:i + 500])}" for i in range(0, len(keys), 500)])
    log(f"redis: {len(keys)} cache keys dropped")


# --------------------------------------------------------------------------------------------- regrow

def regrow(plan, ledger, factor, months, end, seed=11):
    """Re-time the plan and ledger in memory as a platform growing steadily: `months` of history
    ending at `end` (now, so the charts run up to today instead of stopping at the day it was
    seeded), each month about factor^(1/months) busier than the one before.

    Signups and uploads are laid on that curve: people join in their original order, and each
    upload slot goes to a clip of someone who has joined by then. Everything else keeps its distance
    from what it answers (a like from its video, a reply from its parent, a follow from the
    follower's join): the first three days as they were, the rest scaled to the time left before
    the end. Anything that would still land before its author joined is pushed past the join by a day
    or so, and a ban past the banned user's last act. Pending reports keep their age: the
    stale-report sweep reads it.
    """
    t1 = end  # the new end; `was` is the planned one
    was = parse(plan["t1"])
    n0 = t1 - timedelta(days=round(months * 30.44))
    span, rnd = t1 - n0, random.Random(seed)
    last = t1 - timedelta(minutes=1)
    burst = timedelta(days=3)

    def curve(q):  # share of all signups (or uploads) so far -> instant, density growing by `factor`
        return n0 + span * (math.log1p(q * (factor - 1)) / math.log(factor))

    def keep(t, old, new):
        """`t` was this long after `old`; the same, measured from `new`."""
        d = parse(t) - old
        if d <= burst or t1 - new <= burst or was - old <= burst:
            return min(new + max(d, timedelta(0)), last)
        return new + burst + (d - burst) * ((t1 - new - burst) / (was - old - burst))

    def after(t, *floors):
        floor = max(floors, default=t)
        if t <= floor:
            t = floor + timedelta(hours=rnd.expovariate(1 / 24))
        return min(t, last)

    users = sorted(plan["users"], key=lambda u: u["join_at"])
    old_join = {u["username"]: parse(u["join_at"]) for u in users}
    join = {u["username"]: curve((i + rnd.random()) / len(users)) for i, u in enumerate(users)}
    for u in users:
        u["join_at"] = iso(join[u["username"]])

    # Upload slots on the curve, each keeping the hour of day of the clip it goes to.
    keys = list(ledger.videos)
    old_vat = {key: parse(ledger.videos[key]["at"]) for key in keys}
    slots = [curve((i + rnd.random()) / len(keys)) for i in range(len(keys))]
    waiting = sorted(keys, key=lambda key: join[ledger.videos[key]["owner"]])
    # A hit (tens of thousands of views) lands in the later half, when the audience is there to watch it.
    pull = {key: 0.55 + 0.45 * rnd.random() if ledger.videos[key]["views"] >= 10000 else rnd.random() for key in keys}
    vat, ready = {}, []
    for t in slots:
        while waiting and join[ledger.videos[waiting[0]]["owner"]] + timedelta(hours=1) <= t:
            ready.append(waiting.pop(0))
        if not ready:
            continue
        key = min(ready, key=lambda k: pull[k])
        ready.remove(key)
        local = old_vat[key].astimezone(scenario.VN)
        day = t.astimezone(scenario.VN).replace(hour=local.hour, minute=local.minute, second=local.second)
        vat[key] = after(day.astimezone(t1.tzinfo), join[ledger.videos[key]["owner"]])
    for key in ready + waiting:  # slots ran out before their owner joined: shortly after the join
        vat[key] = after(join[ledger.videos[key]["owner"]])
    for key in keys:
        ledger.videos[key]["at"] = iso(vat[key])

    busy = defaultdict(lambda: n0)  # each user's last act, so a ban can come after it

    def act(who, t, *floors):
        t = after(t, join[who], *floors)
        busy[who] = max(busy[who], t)
        return iso(t)

    for section in ("likes", "shares", "reposts", "saves"):
        for x in getattr(ledger, section):
            if parse(x["at"]) <= was:  # later ones were sent by --refresh at their real time
                v = x["video"]
                x["at"] = act(x["actor"], keep(x["at"], old_vat[v], vat[v]), vat[v])
    old_cat, cat = {}, {}
    for cid, c in sorted(ledger.comments.items(), key=lambda kv: kv[1]["at"]):
        old_cat[cid] = parse(c["at"])
        parent = c.get("parent_cid")
        if parent in cat:
            t = keep(c["at"], old_cat[parent], cat[parent])
            c["at"] = act(c["author"], t, cat[parent], vat[c["video"]])
        else:
            c["at"] = act(c["author"], keep(c["at"], old_vat[c["video"]], vat[c["video"]]), vat[c["video"]])
        cat[cid] = parse(c["at"])
    for x in ledger.comment_likes:
        if x["cid"] in cat:
            x["at"] = act(x["actor"], keep(x["at"], old_cat[x["cid"]], cat[x["cid"]]), cat[x["cid"]])
    for section, a, b in (("follows", "follower", "following"), ("blocks", "blocker", "blocked"), ("mutes", "muter", "muted")):
        for x in getattr(ledger, section):
            x["at"] = act(x[a], keep(x["at"], old_join[x[a]], join[x[a]]), join[x[b]])
    for c in ledger.chats:
        msgs = c["messages"]
        if not msgs:
            continue
        old_both, both = max(old_join[c["a"]], old_join[c["b"]]), max(join[c["a"]], join[c["b"]])
        start = min(after(keep(msgs[0]["at"], old_both, both), both), last - timedelta(minutes=len(msgs)))
        g = [parse(m["at"]) for m in msgs]
        length = (g[-1] - g[0]).total_seconds()
        scale = min(1.0, (last - start).total_seconds() / length) if length else 1.0
        for m, x in zip(msgs, g):
            m["at"] = iso(start + (x - g[0]) * scale)
            busy[m["from"]] = max(busy[m["from"]], parse(m["at"]))

    for a in plan["admin"]:
        at0 = parse(a["at"])
        if a["kind"] == "report":
            tt, target = a["target_type"], a["target"]
            if tt == "VIDEO":
                t, floors = keep(a["at"], old_vat[target], vat[target]), [vat[target]]
            elif tt == "USER":
                t, floors = keep(a["at"], old_join[target], join[target]), [join[target]]
            else:
                t, floors = (keep(a["at"], old_cat[target], cat[target]), [cat[target]]) if target in cat else (at0 + (t1 - was), [])
            if a["outcome"] == "pending":  # as old as it was at seeding
                t = at0 + (t1 - was)
            if a["outcome"] == "BAN_USER":
                floors.append(busy[target])
            t = after(t, join[a["reporter"]], *floors)
            if a.get("resolved_at"):
                a["resolved_at"] = iso(min(t + (parse(a["resolved_at"]) - at0), last))
        elif a["kind"] in ("warn", "revoke_sessions"):
            t = after(keep(a["at"], old_join[a["target"]], join[a["target"]]), join[a["target"]])
        elif a["kind"] == "remove_comment" and a["cid"] in cat:
            t = after(keep(a["at"], old_cat[a["cid"]], cat[a["cid"]]), cat[a["cid"]])
        else:
            t = min(at0 + (t1 - was), last)
        a["at"] = iso(t)
        rec = ledger.actions.get(a["key"])
        if rec and rec.get("at"):
            rec["at"] = iso(min(t + (parse(rec["at"]) - at0), last))
    for key, rec in ledger.actions.items():
        video = key[len("restore-"):]
        if key.startswith("restore-") and rec and video in vat:
            rec["at"] = iso(after(keep(rec["at"], old_vat[video], vat[video]), vat[video]))
    plan["t1"] = iso(t1)  # the watch rows are drawn up to here
    return join


def check_growth(plan, ledger):
    """Asserts the orderings `regrow` must keep, and prints the monthly counts the admin charts show."""
    t1 = plan["t1"]
    join = {u["username"]: u["join_at"] for u in plan["users"]}
    vat = {k: r["at"] for k, r in ledger.videos.items()}
    for k, r in ledger.videos.items():
        assert join[r["owner"]] <= r["at"] <= t1, (k, r["at"])
    for section in ("likes", "shares", "reposts", "saves"):
        for x in getattr(ledger, section):
            assert x["at"] > t1 or (join[x["actor"]] <= x["at"] and vat[x["video"]] <= x["at"]), (section, x)
    for cid, c in ledger.comments.items():
        assert join[c["author"]] <= c["at"] and vat[c["video"]] <= c["at"] <= t1, (cid, c["at"])
        p = ledger.comments.get(c.get("parent_cid") or "")
        assert not p or p["at"] <= c["at"], cid
    for f in ledger.follows:
        assert join[f["follower"]] <= f["at"] and join[f["following"]] <= f["at"] <= t1, f
    for c in ledger.chats:
        times = [m["at"] for m in c["messages"]]
        assert times == sorted(times) and (not times or (join[c["a"]] <= times[0] and join[c["b"]] <= times[0] and times[-1] <= t1)), c["id"]
    for a in plan["admin"]:
        assert a["at"] <= t1 and (not a.get("resolved_at") or a["at"] <= a["resolved_at"] <= t1), a["key"]
    months = defaultdict(lambda: [0, 0, 0])
    for j in join.values():
        months[j[:7]][0] += 1
    for r in ledger.videos.values():
        months[r["at"][:7]][1] += 1
    for x in ledger.likes + ledger.shares + list(ledger.comments.values()):
        months[x["at"][:7]][2] += 1
    print("month    signups  uploads  likes+shares+comments")
    for m in sorted(months):
        print(f"{m}  {months[m][0]:7}  {months[m][1]:7}  {months[m][2]:7}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("media_dir", nargs="?", default="~/Projects/EProject/EProject4/seed-media")
    ap.add_argument("--seed", type=int, default=2026)
    ap.add_argument("--only", help="comma-separated stores: auth,user,video,es,reco,cassandra,chat,notifications,admin,clickhouse,realism,redis")
    ap.add_argument("--grow", type=float, help="re-time as steady growth, last month about this many times the first (e.g. 8)")
    ap.add_argument("--months", type=int, default=12, help="history length for --grow")
    ap.add_argument("--check", action="store_true", help="with --grow: verify and print the monthly counts, write nothing")
    args = ap.parse_args()
    media_dir = Path(args.media_dir).expanduser()
    manifest = json.loads((media_dir / "manifest.json").read_text())
    ledger = Ledger(media_dir / "ledger.json")
    plan = scenario.build(manifest, seed=args.seed, now=parse(ledger.admin["t1"]))
    waiting = [k for k, r in ledger.videos.items() if not _finished(r)]
    if waiting:
        raise SystemExit(f"{len(waiting)} videos still in the pipeline; run seed.py --phase videos first")
    end = parse(ledger.admin["t1"])
    if args.grow:
        end = datetime.now(timezone.utc)
        regrow(plan, ledger, args.grow, args.months, end)
        check_growth(plan, ledger)
        if args.check:
            return
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
        ("realism", lambda: __import__("realism").backdate(ledger, end)),  # after auth: it re-times its own accounts
        ("redis", lambda: flush_redis_caches(ledger)),
    ]
    only = set(args.only.split(",")) if args.only else None
    for name, fn in steps:
        if only is None or name in only:
            fn()
    log("backdate done")


if __name__ == "__main__":
    main()
