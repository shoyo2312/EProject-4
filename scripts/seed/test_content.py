import re, pathlib, sys
sys.path.insert(0, str(pathlib.Path(__file__).parent))
import content as c
BLOCK = pathlib.Path("libs/moderation-lib/src/main/resources/moderation/blocklist-vi.txt")
terms = [l.split("|")[0].strip().lower() for l in BLOCK.read_text().splitlines()
         if l.strip() and not l.startswith("#") and "|" in l]
texts = [*c.COMMENTS, *c.REPLIES, *[l for t in c.CHAT_THREADS for l in t], *c.STORY_CAPTIONS,
         *[s for v in c.CAPTIONS.values() for p in v for s in p], *[s for p in c.HELD_CAPTIONS for s in p],
         *[p["bio"] for p in c.PERSONAS], *[p["display_name"] for p in c.PERSONAS]]
assert len(c.PERSONAS) == 50 and len({p["username"] for p in c.PERSONAS}) == 50
assert all(re.fullmatch(r"[a-z][a-z0-9._]{2,49}", p["username"]) for p in c.PERSONAS)
assert sum(p["role"] == "creator" for p in c.PERSONAS) == 8 and sum(p["role"] == "regular" for p in c.PERSONAS) == 17
assert c.PERSONAS[0]["role"] == "creator" and not (c.PERSONAS[0]["spam"] or c.PERSONAS[0]["temp_ban"] or c.PERSONAS[0]["private"])
assert sum(p["spam"] for p in c.PERSONAS) == 1 and sum(p["temp_ban"] for p in c.PERSONAS) == 1 and sum(p["private"] for p in c.PERSONAS) == 2
assert sum(p["keyword_filter"] for p in c.PERSONAS) == 1
assert all(1 <= len(p["topics"]) <= 3 for p in c.PERSONAS)
assert len(c.COMMENTS) >= 400 and len(set(c.COMMENTS)) == len(c.COMMENTS) and max(len(s) for s in c.COMMENTS) <= 150
assert len(c.REPLIES) >= 120 and len(set(c.REPLIES)) == len(c.REPLIES)
assert len(c.CHAT_THREADS) >= 15 and all(5 <= len(t) <= 20 for t in c.CHAT_THREADS)
assert all(len(v) >= 12 for v in c.CAPTIONS.values()) and len(c.HELD_CAPTIONS) == 3
assert all(len(v) >= 6 for v in c.HASHTAGS.values()) and set(c.HASHTAGS) == set(c.CAPTIONS)
assert all(re.search(r"\d{3,4}[ .]?\d{3}[ .]?\d{3,4}|https?://", d) for _, d in c.HELD_CAPTIONS)
assert len(c.NON_EXPIRING_REASONS) == 4 and all(len(v) >= 4 for v in c.REPORT_REASONS.values())
assert len(c.STORY_CAPTIONS) >= 12
for s in texts:
    low = s.lower()
    hit = [t for t in terms if t and re.search(r"(?<![\w])" + re.escape(t) + r"(?![\w])", low)]
    assert not hit, (s, hit)
print("ok")
