import re, pathlib, sys
sys.path.insert(0, str(pathlib.Path(__file__).parent))
import content as c
BLOCK = pathlib.Path("libs/moderation-lib/src/main/resources/moderation/blocklist-vi.txt")
terms = [l.split("|")[0].strip().lower() for l in BLOCK.read_text().splitlines()
         if l.strip() and not l.startswith("#") and "|" in l]
texts = [*c.COMMENTS, *c.REPLIES, *[l for t in c.CHAT_THREADS for l in t], *c.STORY_CAPTIONS,
         *[s for p in c.HELD_CAPTIONS for s in p],
         *[p["bio"] for p in c.PERSONAS], *[p["display_name"] for p in c.PERSONAS]]
assert len(c.PERSONAS) == 200 and len({p["username"] for p in c.PERSONAS}) == 200
assert all(re.fullmatch(r"[a-z][a-z0-9._]{2,49}", p["username"]) for p in c.PERSONAS)
assert sum(p["role"] == "creator" for p in c.PERSONAS) == 26 and sum(p["role"] == "regular" for p in c.PERSONAS) == 64
assert c.PERSONAS[0]["role"] == "creator" and not (c.PERSONAS[0]["spam"] or c.PERSONAS[0]["temp_ban"] or c.PERSONAS[0]["private"])
assert sum(p["spam"] for p in c.PERSONAS) == 2 and sum(p["temp_ban"] for p in c.PERSONAS) == 2 and sum(p["private"] for p in c.PERSONAS) == 4
assert sum(p["keyword_filter"] for p in c.PERSONAS) == 1
assert all(1 <= len(p["topics"]) <= 3 for p in c.PERSONAS)
assert len(c.COMMENTS) >= 200 and len(set(c.COMMENTS)) == len(c.COMMENTS) and max(len(s) for s in c.COMMENTS) <= 150
assert len(c.REPLIES) >= 100 and len(set(c.REPLIES)) == len(c.REPLIES)
assert len(c.CHAT_THREADS) >= 15 and all(5 <= len(t) <= 20 for t in c.CHAT_THREADS)
assert len(c.HELD_CAPTIONS) == 3
assert all(re.search(r"\d{3,4}[ .]?\d{3}[ .]?\d{3,4}|https?://", d) for _, d in c.HELD_CAPTIONS)
assert len(c.NON_EXPIRING_REASONS) == 4 and all(len(v) >= 4 for v in c.REPORT_REASONS.values())
assert len(c.STORY_CAPTIONS) >= 12
import json
CAPS = pathlib.Path(__file__).with_name("captions.json")
if CAPS.exists():  # one entry per clip in the manifest, written against its thumbnail
    caps = json.loads(CAPS.read_text())
    manifest = pathlib.Path(sys.argv[1]).expanduser() if sys.argv[1:] else None
    if manifest and manifest.exists():
        ids = {str(v["id"]) for v in json.loads(manifest.read_text())["videos"]}
        assert ids <= set(caps), sorted(ids - set(caps))[:10]
    for k, e in caps.items():
        imported = not e["comments"]  # original caption from import-ytdlp.py, no clip-specific comments
        assert set(e) == {"title", "description", "hashtags", "comments", "replies"}, k
        assert 1 <= len(e["title"]) <= 150 and len(e["description"]) <= 2000, k
        assert (1 if imported else 3) <= len(e["hashtags"]) <= 10 and all(re.fullmatch(r"[a-z0-9]{2,50}", h) for h in e["hashtags"]), (k, e["hashtags"])
        assert imported or (6 <= len(e["comments"]) and len(set(e["comments"])) == len(e["comments"]) and all(1 <= len(x) <= 150 for x in e["comments"])), k
        assert imported or (3 <= len(e["replies"]) and all(1 <= len(x) <= 150 for x in e["replies"])), k
        texts += [e["title"], e["description"], *e["comments"], *e["replies"]]
for s in texts:
    low = s.lower()
    hit = [t for t in terms if t and re.search(r"(?<![\w])" + re.escape(t) + r"(?![\w])", low)]
    assert not hit, (s, hit)
print("ok")
