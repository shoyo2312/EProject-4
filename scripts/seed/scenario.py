"""Deterministic planner: manifest + content banks -> one chronological event list with planned instants.

    python3 -I scenario.py --check <manifest.json>   # self-check, prints totals

Pure: no I/O besides reading the manifest. Everything random comes from random.Random(seed).
"""
import json
import math
import random
import sys
from collections import Counter
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import content as C  # noqa: E402

VN = timezone(timedelta(hours=7))
T0 = datetime(2026, 6, 27, 0, 0, tzinfo=VN).astimezone(timezone.utc)
DAY = timedelta(days=1)
API_LIKE_CAP = 40


def iso(dt):
    dt = dt.astimezone(timezone.utc)
    return dt.strftime("%Y-%m-%dT%H:%M:%S.") + f"{dt.microsecond // 1000:03d}Z"


def parse(s):
    return datetime.strptime(s, "%Y-%m-%dT%H:%M:%S.%fZ").replace(tzinfo=timezone.utc)


# --------------------------------------------------------------------------------------------- time helpers

def _join_curve(rnd, n, t0, t1):
    """Front-loaded joins over the first 10 weeks: beta(1,3) over 70 days."""
    return sorted(t0 + timedelta(days=rnd.betavariate(1.0, 3.0) * 70) for _ in range(n))


def _posting_hour(rnd):
    h = rnd.choice([11, 12, 13, 14, 19, 20, 21, 22, 23] * 3 + list(range(7, 24)))
    return timedelta(hours=h, minutes=rnd.randrange(60), seconds=rnd.randrange(60))


def _video_slots(rnd, n, t0, t1):
    """Weekly counts between n/25 and n/12.5 (8–16 for 200 clips), at most 6 per day, hours weighted
    to 11–14 and 19–23 local time."""
    weeks = max(1, int((t1 - t0) / timedelta(weeks=1)))
    lo, hi = max(1, round(n / 25)), min(42, max(2, round(n / 12.5)))
    counts = [rnd.randint(lo, hi) for _ in range(weeks)]
    scale = n / sum(counts)
    counts = [max(lo, min(hi, round(c * scale))) for c in counts]
    while sum(counts) != n and weeks * lo <= n <= weeks * hi:
        i = rnd.randrange(weeks)
        if sum(counts) < n and counts[i] < hi:
            counts[i] += 1
        elif sum(counts) > n and counts[i] > lo:
            counts[i] -= 1
    slots = []
    for w, c in enumerate(counts):
        week_start = (t0 + timedelta(weeks=w)).astimezone(VN).replace(hour=0, minute=0, second=0, microsecond=0)
        per_day = [0] * 7
        for _ in range(c):
            d = rnd.choice([i for i in range(7) if per_day[i] < 6])
            per_day[d] += 1
            at = week_start + timedelta(days=d) + _posting_hour(rnd)
            if at < t1 - timedelta(hours=2):
                slots.append(at.astimezone(timezone.utc))
    return sorted(slots)


def _burst_times(rnd, start, t1, n):
    """60 % in the first 3 days after start (exponential, mean 1 day), 40 % uniform until t1."""
    out = []
    span = (t1 - start).total_seconds()
    for _ in range(n):
        if rnd.random() < 0.6 or span < 3 * 86400:
            dt = min(rnd.expovariate(1 / 86400.0), min(3 * 86400, span))
        else:
            dt = rnd.uniform(3 * 86400, span)
        out.append(start + timedelta(seconds=dt + rnd.random()))
    return sorted(out)


def _zipf_counters(rnd, rank, n):
    if rank <= 10:
        views = int(60000 - (rank - 1) * 4000 + rnd.randint(-2000, 2000))
        likes = int(max(1000, min(4000, views * rnd.uniform(0.045, 0.07))))
    elif rank <= 100:
        f = (rank - 10) / 90
        views = int(math.exp(math.log(2000) + f * (math.log(300) - math.log(2000))) * rnd.uniform(0.8, 1.25))
        likes = int(views * rnd.uniform(0.05, 0.09))
    else:
        f = (rank - 100) / max(1, n - 100)
        views = int(300 - f * 270 + rnd.randint(-10, 10))
        likes = int(views * rnd.uniform(0.04, 0.08))
    views = max(5, views)
    likes = max(0, likes)
    return {
        "views": views,
        "likes": likes,
        "comments": int(likes * 0.04) + (1 if rank <= 100 else rnd.randint(0, 1)),
        "shares": int(likes * 0.02),
        "reposts": int(likes * 0.01),
        "saves": int(likes * 0.03),
        "completions": int(views * rnd.uniform(0.3, 0.6)),
    }


# --------------------------------------------------------------------------------------------- build

def captions():
    return json.loads(Path(__file__).with_name("captions.json").read_text())


def build(manifest, seed=2026, now=None):
    caps = captions()
    rnd = random.Random(seed)
    t1 = (now or datetime.now(timezone.utc)).astimezone(timezone.utc)
    personas = [dict(p) for p in C.PERSONAS]
    by_name = {p["username"]: p for p in personas}

    # ---- users
    joins = _join_curve(rnd, len(personas), T0, t1)
    creators = [p for p in personas if p["role"] == "creator"]
    others = [p for p in personas if p["role"] != "creator"]
    rnd.shuffle(others)
    late = [p for p in others if p["role"] == "viewer" and not (p["spam"] or p["temp_ban"] or p["private"])][:2]
    ordered = creators + [p for p in others if p not in late]
    for p, j in zip(ordered, joins):
        p["join_at"] = j
    for p in creators:  # creators are in from the first ten days
        p["join_at"] = T0 + timedelta(days=rnd.uniform(0, 10), hours=rnd.uniform(6, 22))
    for p in late:
        p["join_at"] = t1 - timedelta(days=rnd.uniform(2, 14))
    avatars = list(manifest["avatars"])
    for i, p in enumerate(sorted(personas, key=lambda p: p["join_at"])):
        p["avatar"] = avatars[i % len(avatars)]["file"]
        p["settings"] = (
            {"privateAccount": True} if p["private"]
            else {"commentKeywords": ["quảng cáo", "inbox"], "filterSpamOffensive": True} if p["keyword_filter"]
            else None
        )
    spams = [p for p in personas if p["spam"]]
    temps = [p for p in personas if p["temp_ban"]]
    banned_after = {}  # permanent bans 30 and 41 days ago; temporary bans (5 and 7 days) 2 and 3 days ago
    for i, p in enumerate(spams):
        banned_after[p["username"]] = t1 - timedelta(days=30 + 11 * i)
    for i, p in enumerate(temps):
        banned_after[p["username"]] = t1 - timedelta(days=2 + i)

    def active(p, at):
        return p["join_at"] <= at and at < banned_after.get(p["username"], t1 + DAY)

    # ---- follows
    follows, follow_set = [], set()
    private_names = [p["username"] for p in personas if p["private"]]
    request_done = False

    def add_follow(a, b, at):
        nonlocal request_done
        if a is b or (a["username"], b["username"]) in follow_set:
            return
        if b["username"] in private_names:
            if request_done:
                return
            request_done = True
        at = max(at, a["join_at"], b["join_at"]) + timedelta(seconds=rnd.uniform(60, 3600))
        if at >= t1:
            return
        follow_set.add((a["username"], b["username"]))
        follows.append({"follower": a["username"], "following": b["username"], "at": at,
                        "kind": "follow_request" if b["username"] in private_names else "follow"})

    for a in personas:
        for b in personas:
            if a is b:
                continue
            prob = {"creator": 0.75, "regular": 0.25, "viewer": 0.05}[b["role"]]
            if rnd.random() < prob:
                add_follow(a, b, a["join_at"] + timedelta(days=rnd.uniform(0, 14)))
    core = [p for p in personas if p["role"] != "viewer"]
    for a in core:
        for b in core:
            if a is not b and rnd.random() < 0.3:
                add_follow(a, b, max(a["join_at"], b["join_at"]) + timedelta(days=rnd.uniform(0, 21)))
                add_follow(b, a, max(a["join_at"], b["join_at"]) + timedelta(days=rnd.uniform(0, 21)))
    follows.sort(key=lambda f: f["at"])
    mutual = {(a, b) for (a, b) in follow_set if (b, a) in follow_set}

    # ---- blocks (pairs with no follow edge: a block severs both edges) and mutes (of someone followed)
    blocks, blocked_pairs = [], set()
    for p in rnd.sample([p for p in personas if p["username"] not in banned_after], 20):
        cands = [q for q in personas if q is not p and q["username"] not in banned_after
                 and (p["username"], q["username"]) not in follow_set and (q["username"], p["username"]) not in follow_set]
        q = rnd.choice(cands)
        at = max(p["join_at"], q["join_at"]) + timedelta(days=rnd.uniform(1, 60))
        if at < t1:
            blocks.append({"blocker": p["username"], "blocked": q["username"], "at": at})
            blocked_pairs |= {(p["username"], q["username"]), (q["username"], p["username"])}
    mutes = []
    for f in rnd.sample([f for f in follows if f["kind"] == "follow" and f["following"] not in banned_after], 30):
        at = f["at"] + timedelta(days=rnd.uniform(1, 40))
        if at < t1:
            mutes.append({"muter": f["follower"], "muted": f["following"], "at": at})

    # ---- videos: owners by quota and topic
    pool = list(manifest["videos"])
    rnd.shuffle(pool)
    quota = {}
    for p in personas:
        quota[p["username"]] = {"creator": rnd.randint(10, 20), "regular": rnd.randint(3, 8), "viewer": rnd.choice([0, 0, 1, 1, 2])}[p["role"]]
    total = sum(quota.values())
    names = sorted(quota, key=lambda n: -quota[n])
    i = 0
    while total != len(pool):
        name = names[i % len(names)]
        step = 1 if total < len(pool) else -1
        if quota[name] + step >= 0 and (step > 0 or quota[name] > 1):
            quota[name] += step
            total += step
        i += 1
    assignments = []
    for p in sorted(personas, key=lambda p: {"creator": 0, "regular": 1, "viewer": 2}[p["role"]]):
        need = quota[p["username"]]
        mine = [v for v in pool if v["topic"] in p["topics"]][:need]
        for v in mine:
            pool.remove(v)
        rest = pool[:need - len(mine)]
        for v in rest:
            pool.remove(v)
        assignments += [(p, v) for v in mine + rest]
    assert not pool, "every clip must be assigned"
    slots = _video_slots(rnd, len(assignments), T0, t1)
    rnd.shuffle(assignments)
    videos, remaining = [], list(assignments)
    per_day = Counter(s.astimezone(VN).date() for s in slots)

    def room(at, slot=None):  # a clip pushed past its slot (owner not joined yet) must not make a seventh post that day
        if slot:
            per_day[slot.astimezone(VN).date()] -= 1
        while per_day[at.astimezone(VN).date()] >= 6:
            at += DAY
        per_day[at.astimezone(VN).date()] += 1
        return at

    for slot in slots:
        ok = [i for i, (p, _) in enumerate(remaining) if p["join_at"] <= slot]
        i = rnd.choice(ok) if ok else 0
        p, v = remaining.pop(i)
        videos.append({"key": v["file"][:-4], "id": v["id"], "file": v["file"], "owner": p["username"], "topic": v["topic"],
                       "duration": v["duration"], "at": room(max(slot, p["join_at"] + timedelta(hours=1)), slot)})
    for p, v in remaining:  # clips without a slot: post them within the last day or so
        videos.append({"key": v["file"][:-4], "id": v["id"], "file": v["file"], "owner": p["username"], "topic": v["topic"],
                       "duration": v["duration"], "at": room(max(t1 - timedelta(hours=rnd.uniform(3, 30)), p["join_at"] + timedelta(hours=1)))})
    videos.sort(key=lambda v: v["at"])
    held_pick = rnd.sample([v for v in videos if by_name[v["owner"]]["role"] == "regular" and v["owner"] not in banned_after], 3)
    own_comments, own_replies = {}, {}
    for v in videos:
        cap = caps[str(v["id"])]  # written against the clip's thumbnail, or its original caption (import-ytdlp.py)
        v["title"], v["description"], v["tags"] = cap["title"], "", cap["hashtags"][:10]  # TikTok has one caption: title + tags
        own_comments[v["key"]] = rnd.sample(cap["comments"], len(cap["comments"]))
        own_replies[v["key"]] = list(cap["replies"])
        v["held"] = v in held_pick
        v["visibility"] = "PUBLIC" if v["held"] else rnd.choices(["PUBLIC", "FRIENDS", "PRIVATE"], [90, 6, 4])[0]
        v["taken_down_at"] = None
    for i, (title, desc) in enumerate(C.HELD_CAPTIONS):
        held_pick[i]["title"], held_pick[i]["description"] = title, desc
    ranked = sorted(videos, key=lambda v: {"creator": 3, "regular": 1.2, "viewer": 0.5}[by_name[v["owner"]]["role"]] * rnd.random(), reverse=True)
    for rank, v in enumerate(ranked, 1):
        v.update(_zipf_counters(rnd, rank, len(videos)))
        v["rank"] = rank

    # ---- engagement events
    events, cid_counter = [], [0]
    used_text = set()

    def eligible(v, at, exclude=()):
        out = []
        for p in personas:
            if p["username"] == v["owner"] or p["username"] in exclude or not active(p, at):
                continue
            if (p["username"], v["owner"]) in blocked_pairs:
                continue
            if v["visibility"] == "FRIENDS" and (p["username"], v["owner"]) not in mutual:
                continue
            out.append(p)
        return out

    def new_cid():
        cid_counter[0] += 1
        return f"c{cid_counter[0]}"

    for v in videos:
        if v["held"] or v["visibility"] == "PRIVATE":
            continue
        taken = set()
        for at in _burst_times(rnd, v["at"], t1, min(v["likes"], API_LIKE_CAP)):
            cands = eligible(v, at, taken)
            if not cands:
                break
            p = rnd.choice(cands)
            taken.add(p["username"])
            events.append({"kind": "like", "at": at, "actor": p["username"], "video": v["key"]})
        for at in _burst_times(rnd, v["at"], t1, v["comments"]):
            cands = eligible(v, at)
            if not cands:
                break
            p = rnd.choice(cands)
            mine = own_comments[v["key"]]
            text = next((t for t in mine[:1] + rnd.sample(C.COMMENTS, 12) if (p["username"], t) not in used_text and (v["key"], t) not in used_text), None)
            if text is None:
                continue
            if mine and text == mine[0]:
                mine.pop(0)
            used_text.add((p["username"], text))
            used_text.add((v["key"], text))
            cid = new_cid()
            events.append({"kind": "comment", "at": at, "actor": p["username"], "video": v["key"], "text": text, "cid": cid})
            if rnd.random() < 0.28:
                rat = at + timedelta(minutes=rnd.uniform(2, 60 * 36))
                if rat < t1:
                    owner = by_name[v["owner"]]
                    replier = owner if rnd.random() < 0.5 and active(owner, rat) else rnd.choice(eligible(v, rat) or [owner])
                    events.append({"kind": "reply", "at": rat, "actor": replier["username"], "video": v["key"],
                                   "parent_cid": cid, "cid": new_cid(),
                                   "text": rnd.choice(own_replies[v["key"]]) if own_replies[v["key"]] and rnd.random() < 0.6 else rnd.choice(C.REPLIES)})
        for kind, n in (("share", v["shares"]), ("repost", v["reposts"]), ("save", v["saves"])):
            taken = set()
            for at in _burst_times(rnd, v["at"], t1, n):
                cands = eligible(v, at, taken)
                if not cands:
                    break
                p = rnd.choice(cands)
                taken.add(p["username"])
                events.append({"kind": kind, "at": at, "actor": p["username"], "video": v["key"]})
    all_comments = [e for e in events if e["kind"] == "comment"]
    vid_by_key = {v["key"]: v for v in videos}
    for ev in rnd.sample(all_comments, min(80, len(all_comments))):
        v = vid_by_key[ev["video"]]
        at = ev["at"] + timedelta(hours=rnd.uniform(0.1, 72))
        cands = eligible(v, at, {ev["actor"]})
        if at < t1 and cands:
            events.append({"kind": "comment_like", "at": at, "actor": rnd.choice(cands)["username"], "video": v["key"], "cid": ev["cid"]})
    events.sort(key=lambda e: e["at"])

    # ---- chats (mutual pairs, both active)
    pairs = sorted({tuple(sorted(x)) for x in mutual if x[0] not in banned_after and x[1] not in banned_after})
    rnd.shuffle(pairs)
    chats = []
    for i, thread in enumerate(C.CHAT_THREADS):
        a, b = pairs[i]
        start = max(by_name[a]["join_at"], by_name[b]["join_at"]) + timedelta(days=rnd.uniform(1, 30))
        if i < 5:
            start = t1 - timedelta(hours=rnd.uniform(2, 40))
        at, msgs = start, []
        for j, text in enumerate(thread):
            msgs.append({"from": a if j % 2 == 0 else b, "text": text, "at": at})
            at += timedelta(minutes=rnd.uniform(1, 90)) if rnd.random() < 0.8 else timedelta(hours=rnd.uniform(2, 30))
        msgs = [m for m in msgs if m["at"] < t1] or msgs[:1]
        chats.append({"a": a, "b": b, "messages": msgs})

    # ---- stories (fresh)
    story_owners = rnd.sample([p for p in personas if p["role"] != "viewer" and p["username"] not in banned_after], 20)
    stories = []
    for i, img in enumerate(rnd.sample(manifest["stories"], 40)):
        owner = story_owners[i % 20]
        stories.append({"owner": owner["username"], "file": img["file"], "type": img.get("type", "image"),
                        "caption": C.STORY_CAPTIONS[i % len(C.STORY_CAPTIONS)],
                        "visibility": "PRIVATE" if i >= 38 else "FRIENDS" if i >= 32 else "PUBLIC",
                        "at": t1 - timedelta(hours=rnd.uniform(0.5, 20))})

    # ---- admin: 60 reports (24 VIDEO, 16 USER, 20 COMMENT) + 10 direct comment removals + 4 restores
    # + 3 direct warnings + 1 session revocation + the managed moderation terms
    admin = []
    public_videos = [v for v in videos if v["visibility"] == "PUBLIC" and not v["held"] and v["owner"] not in banned_after]
    reporters = [p for p in personas if p["role"] == "viewer" and p["username"] not in banned_after]

    def reporter_for(at, exclude=()):
        cands = [p for p in reporters if p["join_at"] <= at and p["username"] not in exclude]
        return rnd.choice(cands) if cands else None

    def report(at, target_type, target, outcome, reason=None, ban_days=None, delay_h=None):
        if reason is None:
            reason = rnd.choice(C.REPORT_REASONS[target_type])
        if outcome == "pending" and at < t1 - timedelta(days=14) and reason not in C.NON_EXPIRING_REASONS:
            reason = rnd.choice(sorted(C.NON_EXPIRING_REASONS & set(C.REPORT_REASONS[target_type])) or sorted(C.NON_EXPIRING_REASONS))
        owner = target if target_type == "USER" else (target["owner"] if target_type == "VIDEO" else target["actor"])
        rep = reporter_for(at, {owner})
        if rep is None:
            return None
        resolved_at = None if outcome == "pending" else at + timedelta(hours=delay_h if delay_h is not None else rnd.uniform(1, 72))
        entry = {"kind": "report", "at": at, "reporter": rep["username"], "target_type": target_type, "reason": reason,
                 "outcome": outcome, "ban_days": ban_days, "resolved_at": resolved_at,
                 "target": target if target_type == "USER" else target["key"] if target_type == "VIDEO" else target["cid"]}
        admin.append(entry)
        return entry

    vids = rnd.sample(public_videos, 24)
    for i, v in enumerate(vids):
        outcome = "TAKEDOWN_VIDEO" if i < 6 else "DISMISS_REPORT" if i < 16 else "pending"
        at = v["at"] + timedelta(days=rnd.uniform(0.2, 20)) if outcome != "pending" else t1 - timedelta(days=rnd.uniform(0.3, 13))
        at = min(max(at, v["at"] + timedelta(hours=1)), t1 - timedelta(hours=1))
        e = report(at, "VIDEO", v, outcome)
        if e and outcome == "TAKEDOWN_VIDEO":
            v["taken_down_at"] = e["resolved_at"]
    for p in spams:
        report(banned_after[p["username"]] - DAY, "USER", p["username"], "BAN_USER", reason="Deceptive behavior and spam", ban_days=None, delay_h=24)
    for i, p in enumerate(temps):
        report(banned_after[p["username"]] - DAY, "USER", p["username"], "BAN_USER", reason="Hate and harassment", ban_days=5 + 2 * i, delay_h=24)
    warn_targets = rnd.sample([p for p in personas if p["role"] != "creator" and p["username"] not in banned_after and p is not personas[0]], 12)
    for i, p in enumerate(warn_targets):
        outcome = "WARN_USER" if i < 10 else "DISMISS_REPORT"
        at = t1 - timedelta(days=rnd.uniform(1, 100)) if outcome != "pending" else t1 - timedelta(days=rnd.uniform(0.5, 20))
        report(min(max(at, p["join_at"] + DAY), t1 - timedelta(hours=1)), "USER", p["username"], outcome)
    banned_comments = [e for e in all_comments if e["actor"] in banned_after]
    other_comments = [e for e in all_comments if e["actor"] not in banned_after and e["actor"] != personas[0]["username"]]
    rnd.shuffle(other_comments)
    removal_authors, removal_reports = set(), []
    for e in other_comments:
        if e["actor"] not in removal_authors and len(removal_reports) < 8:
            removal_authors.add(e["actor"])
            removal_reports.append(e)
    rest = [e for e in other_comments if e not in removal_reports][:12]
    for i, e in enumerate(removal_reports + rest):
        outcome = "REMOVE_COMMENT" if i < 8 else "DISMISS_REPORT" if i < 12 else "pending"
        at = parse(iso(e["at"])) if False else e["at"]
        at = at + timedelta(hours=rnd.uniform(1, 200)) if outcome != "pending" else max(at + timedelta(hours=1), t1 - timedelta(days=rnd.uniform(0.5, 13)))
        report(min(at, t1 - timedelta(hours=1)), "COMMENT", e, outcome)
    for e in rnd.sample(banned_comments, min(10, len(banned_comments))):
        admin.append({"kind": "remove_comment", "at": min(e["at"] + timedelta(hours=rnd.uniform(1, 100)), t1 - timedelta(hours=1)),
                      "video": e["video"], "cid": e["cid"], "reason": "Spam"})
    for _ in range(4):
        admin.append({"kind": "restore", "at": t1 - timedelta(days=rnd.uniform(5, 60)), "video": None, "reason": "Reviewed: nothing wrong"})
    direct = rnd.sample([p for p in personas if p["role"] != "creator" and p["username"] not in banned_after
                         and p not in warn_targets and p is not personas[0]], 4)
    for p in direct[:3]:
        admin.append({"kind": "warn", "at": min(p["join_at"] + timedelta(days=rnd.uniform(3, 60)), t1 - timedelta(hours=1)),
                      "target": p["username"], "reason": "Repeated off-topic comments"})
    admin.append({"kind": "revoke_sessions", "at": t1 - timedelta(days=rnd.uniform(1, 10)), "target": direct[3]["username"],
                  "reason": "Account owner reported a stolen phone"})
    admin.sort(key=lambda e: e["at"])
    restores = 0
    for a in admin:  # stable keys: the ledger is keyed by these, never by list position
        if a["kind"] == "report":
            a["key"] = f"r:{a['target_type']}:{a['target']}:{a['reporter']}"
        elif a["kind"] == "remove_comment":
            a["key"] = f"rc:{a['cid']}"
        elif a["kind"] in ("warn", "revoke_sessions"):
            a["key"] = f"{a['kind']}:{a['target']}"
        else:
            a["key"] = f"restore:{restores}"
            restores += 1
    terms = [{"term": t, "category": c, "action": act, "note": n} for t, c, act, n in C.ADMIN_TERMS]

    def ser(obj):
        if isinstance(obj, datetime):
            return iso(obj)
        if isinstance(obj, dict):
            return {k: ser(x) for k, x in obj.items()}
        if isinstance(obj, list):
            return [ser(x) for x in obj]
        return obj

    # held comments on the keyword-filter creator's videos — appended last with their own generator so the
    # cids and the randomness of everything above stay stable
    rnd2 = random.Random(seed + 1)
    kf = next(p for p in personas if p["keyword_filter"])
    kf_videos = [v for v in videos if v["owner"] == kf["username"] and v["visibility"] == "PUBLIC" and not v["held"]]
    for text in C.HELD_COMMENTS:
        v = rnd2.choice(kf_videos)
        at = parse(iso(v["at"])) if isinstance(v["at"], str) else v["at"]
        at = at + timedelta(days=rnd2.uniform(0.5, 10))
        cands = [p for p in personas if p["role"] == "viewer" and active(p, at)]
        if at < t1 and cands:
            events.append({"kind": "comment", "at": at, "actor": rnd2.choice(cands)["username"], "video": v["key"], "text": text, "cid": new_cid()})
    events.sort(key=lambda e: e["at"])

    users = sorted(personas, key=lambda p: p["join_at"])
    return ser({"t0": T0, "t1": t1, "users": users, "follows": follows, "blocks": blocks, "mutes": mutes, "videos": videos,
                "events": events, "chats": chats, "stories": stories, "admin": admin, "terms": terms})


# --------------------------------------------------------------------------------------------- check

def check(plan, clips):
    from collections import Counter
    users = {u["username"]: u for u in plan["users"]}
    videos = {v["key"]: v for v in plan["videos"]}
    t1 = parse(plan["t1"])
    assert len(plan["users"]) == 200 and len(plan["videos"]) == clips, (len(plan["users"]), len(plan["videos"]))
    assert sum(v["held"] for v in plan["videos"]) == 3
    for v in plan["videos"]:
        assert v["at"] >= users[v["owner"]]["join_at"], v
    per_day = Counter(parse(v["at"]).astimezone(VN).date() for v in plan["videos"])
    assert max(per_day.values()) <= 6, max(per_day.values())
    for e in plan["events"]:
        assert e["at"] >= videos[e["video"]]["at"], e
        assert e["at"] >= users[e["actor"]]["join_at"], e
    for f in plan["follows"]:
        assert f["at"] >= users[f["follower"]]["join_at"] and f["at"] >= users[f["following"]]["join_at"]
    follow_set = {(f["follower"], f["following"]) for f in plan["follows"]}
    for b in plan["blocks"]:
        assert b["at"] >= users[b["blocker"]]["join_at"] and b["at"] >= users[b["blocked"]]["join_at"]
        assert (b["blocker"], b["blocked"]) not in follow_set and (b["blocked"], b["blocker"]) not in follow_set, b
    assert all((m["muter"], m["muted"]) in follow_set for m in plan["mutes"])
    pairs = [(e["video"], e["text"]) for e in plan["events"] if e["kind"] == "comment"]
    assert len(pairs) == len(set(pairs)), "duplicate comment text on a video"
    likes = sum(e["kind"] == "like" for e in plan["events"])
    assert likes <= 15000, likes
    demo = C.PERSONAS[0]["username"]
    cids = {e["cid"]: e for e in plan["events"] if e["kind"] in ("comment", "reply")}
    reports = [a for a in plan["admin"] if a["kind"] == "report"]
    assert len(reports) == 60, len(reports)
    assert len({a["key"] for a in plan["admin"]}) == len(plan["admin"]), "admin keys must be unique"
    for a in reports:
        if a["outcome"] == "pending":  # StaleReportSweep dismisses expiring reasons after 30 d; leave 16 d for the demo
            assert parse(a["at"]) > t1 - timedelta(days=14) or a["reason"] in C.NON_EXPIRING_REASONS, a
        if a["target_type"] == "COMMENT" and a["outcome"] == "REMOVE_COMMENT":
            assert cids[a["target"]]["actor"] != demo
    for a in plan["admin"]:
        if a["kind"] == "remove_comment":
            assert cids[a["cid"]]["actor"] != demo
    out = {k: len(plan[k]) for k in ("users", "follows", "blocks", "mutes", "videos", "events", "chats", "stories", "admin", "terms")}
    out["events_by_kind"] = dict(Counter(e["kind"] for e in plan["events"]))
    out["report_outcomes"] = dict(Counter(a["outcome"] for a in reports))
    out["follow_requests"] = sum(f["kind"] == "follow_request" for f in plan["follows"])
    out["top_views"] = [v["views"] for v in sorted(plan["videos"], key=lambda v: -v["views"])[:3]]
    out["videos_per_role"] = dict(Counter(users[v["owner"]]["role"] for v in plan["videos"]))
    return out


if __name__ == "__main__":
    if sys.argv[1:2] != ["--check"]:
        print(__doc__)
        sys.exit(2)
    manifest = json.loads(Path(sys.argv[2]).expanduser().read_text())
    now = datetime.now(timezone.utc)
    a = build(manifest, now=now)
    b = build(manifest, now=now)
    assert json.dumps(a, sort_keys=True) == json.dumps(b, sort_keys=True), "build() is not deterministic"
    print(json.dumps(check(a, len(manifest["videos"])), ensure_ascii=False))
    print("ok")
