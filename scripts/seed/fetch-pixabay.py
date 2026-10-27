#!/usr/bin/env python3
"""Download demo media from Pixabay into <out>/<topic>/<id>.<ext> plus manifest.json.

Only clips with an audio track are kept (probed from the first 256 KB before downloading); each clip
also gets its Pixabay thumbnail as <id>.jpg, used to write per-clip captions.

Usage: fetch-pixabay.py <out-dir> [--videos N_PER_TOPIC] [--avatars N] [--stories N]
Key is read from ~/.pixabay_key. Re-runs skip files already on disk.
"""
import json, os, re, sys, time, urllib.parse, urllib.request
from pathlib import Path

_opener = urllib.request.build_opener(); _opener.addheaders = [("User-Agent", "Mozilla/5.0 tiktok-seed")]
urllib.request.install_opener(_opener)
KEY = Path.home().joinpath(".pixabay_key").read_text().strip()
API = "https://pixabay.com/api/"

# topic -> search queries (several per topic so 10 clips don't all look alike)
TOPICS = {
    "food":     ["street food", "cooking kitchen", "dessert cake", "coffee latte"],
    "travel":   ["beach sunset", "mountain hiking", "city night street", "temple asia"],
    "pets":     ["dog playing", "cat kitten", "puppy", "parrot bird"],
    "fitness":  ["gym workout", "running", "yoga", "boxing training"],
    "nature":   ["waterfall", "forest rain", "ocean waves", "flowers field"],
    "dance":    ["dancing", "breakdance street", "ballet", "dance performance stage"],
    "music":    ["guitar playing", "piano", "dj concert", "singing"],
    "study":    ["study desk", "library books", "writing notes", "laptop coding"],
    "fashion":  ["fashion model", "streetwear", "makeup", "shopping mall"],
    "art":      ["painting art", "pottery", "drawing sketch", "graffiti"],
    "sports":   ["skateboard", "football soccer", "basketball", "surfing"],
    "cars":     ["car driving", "motorcycle", "drone city", "train"],
    "kids":     ["children playing", "family picnic", "baby"],
    "tech":     ["robot", "circuit board", "smartphone", "3d printer"],
    "garden":   ["gardening plants", "farm harvest", "bees honey"],
    "night":    ["fireworks", "neon lights", "campfire", "stars timelapse"],
    "animals":  ["elephant", "horse running", "fish aquarium", "squirrel"],
    "drinks":   ["bubble tea", "smoothie", "cocktail mocktail", "tea ceremony"],
    "home":     ["cleaning house", "diy craft", "candle cozy", "cooking breakfast"],
    "vietnam":  ["vietnam", "hanoi", "rice field", "lantern festival"],
}
AVATAR_QUERIES = ["portrait face", "woman portrait", "man portrait", "smile person"]
STORY_QUERIES = ["food", "travel", "sunset", "cafe", "city", "flowers", "pet", "beach"]


def get(params, retries=3):
    url = API + ("videos/" if params.pop("_videos", False) else "") + "?" + urllib.parse.urlencode({"key": KEY, "safesearch": "true", **params})
    for i in range(retries):
        try:
            with urllib.request.urlopen(url, timeout=60) as r:
                if r.status == 429:
                    time.sleep(60); continue
                return json.load(r)
        except Exception as e:  # noqa
            print("  retry", e, file=sys.stderr); time.sleep(5 * (i + 1))
    return {"hits": []}


def download(url, dest: Path):
    if dest.exists() and dest.stat().st_size > 0:
        return False
    tmp = dest.with_suffix(dest.suffix + ".part")
    with urllib.request.urlopen(url, timeout=300) as r, open(tmp, "wb") as f:
        while chunk := r.read(1 << 16):
            f.write(chunk)
    tmp.rename(dest)
    return True


# stock animations and VJ loops carry audio but look nothing like a phone-shot post
NOT_FOOTAGE = re.compile(r"\b(3d|cgi|animation|animated|cartoon|render|rendering|motion design|motion graphics|vj loop|dj loop|green screen|chroma key|hops|countdown|timer|wallpaper|whiteboard|hyper space|intro)\b")


def has_audio(url):
    """Pixabay mp4s are faststart, so the moov atom (and its 'soun' handler) sits in the first 256 KB."""
    req = urllib.request.Request(url, headers={"Range": "bytes=0-262143"})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return b"soun" in r.read()
    except Exception as e:  # noqa
        print("  probe failed", e, file=sys.stderr)
        return False


def pick_rendition(videos):
    # prefer medium (720x1280 portrait / 1280x720 landscape); fall back smaller
    for k in ("medium", "small", "tiny"):
        v = videos.get(k)
        if v and v.get("url") and max(v["width"], v["height"]) <= 1920:
            return k, v
    return None, None


def main():
    out = Path(sys.argv[1]).expanduser()
    args = dict(zip(sys.argv[2::2], sys.argv[3::2]))
    per_topic = int(args.get("--videos", 20))
    n_avatars = int(args.get("--avatars", 110))
    n_stories = int(args.get("--stories", 60))
    manifest_path = out / "manifest.json"
    manifest = json.loads(manifest_path.read_text()) if manifest_path.exists() else {"videos": [], "avatars": [], "stories": []}
    seen = {m["id"] for k in manifest for m in manifest[k]}

    def save():
        manifest_path.write_text(json.dumps(manifest, indent=1, ensure_ascii=False))

    # drop clips downloaded before the audio filter existed
    silent = [m for m in manifest["videos"] if NOT_FOOTAGE.search(m["tags"].lower()) or b"soun" not in (out / m["file"]).read_bytes()]
    for m in silent:
        (out / m["file"]).unlink(missing_ok=True)
        (out / m["file"]).with_suffix(".jpg").unlink(missing_ok=True)
    manifest["videos"] = [m for m in manifest["videos"] if m not in silent]
    print("pruned silent / non-footage clips:", len(silent))
    save()

    def add_video(h, topic, q):
        kind, v = pick_rendition(h["videos"])
        if not v:
            return False
        dest = out / topic / f"{h['id']}.mp4"
        print(f"[{topic}] {q!r} -> {dest.name} {v['width']}x{v['height']} {h['duration']}s")
        download(v["url"], dest)
        thumb = (h["videos"].get("medium") or v).get("thumbnail")
        if thumb:
            download(thumb, dest.with_suffix(".jpg"))
        manifest["videos"].append({"id": h["id"], "topic": topic, "query": q, "file": str(dest.relative_to(out)),
                                   "width": v["width"], "height": v["height"], "duration": h["duration"],
                                   "tags": h["tags"], "user": h["user"], "page": h["pageURL"]})
        seen.add(h["id"]); save()
        return True

    for topic, queries in TOPICS.items():
        (out / topic).mkdir(parents=True, exist_ok=True)
        for page in range(1, 6):  # round-robin over queries, deeper pages only when the topic is still short
            for q in queries:
                need = per_topic - sum(1 for m in manifest["videos"] if m["topic"] == topic)
                if need <= 0:
                    break
                cap = -(-need // len(queries)) if page == 1 else need
                hits = get({"_videos": True, "q": q, "per_page": 25, "page": page, "order": "popular"}).get("hits", [])
                for h in hits:
                    if cap <= 0:
                        break
                    if h["id"] in seen or not (5 <= h["duration"] <= 45) or NOT_FOOTAGE.search(h["tags"].lower()):
                        continue
                    probe = h["videos"].get("tiny") or h["videos"].get("small")
                    if not probe or not has_audio(probe["url"]):
                        continue
                    if add_video(h, topic, q):
                        cap -= 1
                        time.sleep(0.5)
        print(f"[{topic}] total", sum(1 for m in manifest["videos"] if m["topic"] == topic))

    # thumbnails for clips downloaded before thumbnails were kept
    for m in manifest["videos"]:
        if "pixabay.com" in (m["page"] or "") and not (out / m["file"]).with_suffix(".jpg").exists():  # yt-dlp clips: not Pixabay ids
            hit = next(iter(get({"_videos": True, "id": m["id"]}).get("hits", [])), None)
            thumb = hit and (hit["videos"].get("medium") or hit["videos"]["small"]).get("thumbnail")
            if thumb:
                download(thumb, (out / m["file"]).with_suffix(".jpg")); time.sleep(0.3)

    def photos(bucket, queries, total, orientation):
        folder = out / bucket
        folder.mkdir(parents=True, exist_ok=True)
        need = total - len(manifest[bucket])
        for q in queries:
            if need <= 0:
                return
            hits = get({"q": q, "image_type": "photo", "orientation": orientation, "per_page": 30, "order": "popular"}).get("hits", [])
            for h in hits:
                if need <= 0:
                    return
                if h["id"] in seen:
                    continue
                url = h.get("largeImageURL") or h["webformatURL"]
                dest = folder / f"{h['id']}.jpg"
                print(f"[{bucket}] {q!r} -> {dest.name}")
                download(url, dest)
                manifest[bucket].append({"id": h["id"], "query": q, "file": str(dest.relative_to(out)), "tags": h["tags"], "user": h["user"], "page": h["pageURL"]})
                seen.add(h["id"]); need -= 1
                save(); time.sleep(0.5)

    photos("avatars", AVATAR_QUERIES, n_avatars, "vertical")
    photos("stories", STORY_QUERIES, n_stories, "vertical")
    print("done:", {k: len(v) for k, v in manifest.items()})


if __name__ == "__main__":
    main()
