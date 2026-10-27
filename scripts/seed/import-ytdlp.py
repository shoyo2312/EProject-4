#!/usr/bin/env python3
"""Add clips downloaded with yt-dlp (<media>/<topic>/<id>.mp4 + <id>.info.json) to manifest.json,
with their original caption in captions.json.

Usage: import-ytdlp.py <media-dir>    # re-runs skip clips already in the manifest
       import-ytdlp.py --check        # self-test of the caption split

The caption's hashtags become the video's tags (accents stripped: tags are [a-z0-9]) and its first line
the title; TikTok has no separate description, so it stays empty. An existing captions.json entry is kept, so a caption edited
by hand to pass the blocklist survives a re-run. Comments and replies stay empty: the planner falls back
to the topic-neutral banks in content.py.
"""
import json, re, sys, unicodedata
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from content import with_topic_tags  # noqa: E402

CAPS = Path(__file__).with_name("captions.json")
HASHTAG = re.compile(r"#(\w+)")


def tag(word):
    plain = unicodedata.normalize("NFD", word.lower().replace("đ", "d"))
    return "".join(ch for ch in plain if ch.isascii() and ch.isalnum())[:50]


def split_caption(text, topic):
    tags = list(dict.fromkeys(t for t in map(tag, HASHTAG.findall(text)) if len(t) >= 2))[:10]
    body = "\n".join(" ".join(line.split()) for line in HASHTAG.sub("", text).splitlines()).strip()
    first = next((line for line in body.splitlines() if line), "")
    title = (first or " ".join("#" + t for t in tags[:3]) or f"Video {topic}")[:150].strip()
    return {"title": title, "description": "", "hashtags": with_topic_tags(tags, topic), "comments": [], "replies": []}


def check():
    c = split_caption("Phở Hà Nội sáng sớm 🍜\nngon xỉu #phởbò #ĐồĂn #fyp #fyp #a", "food")
    assert c["title"] == "Phở Hà Nội sáng sớm 🍜", c
    assert c["description"] == "", c
    assert c["hashtags"] == ["food", "phobo", "doan", "fyp"], c
    assert split_caption("#travel #dalat", "travel")["title"] == "#travel #dalat"
    assert split_caption("", "pets") == {"title": "Video pets", "description": "", "hashtags": ["pets", "animals"],
                                         "comments": [], "replies": []}
    assert with_topic_tags([f"t{i}" for i in range(10)], "drinks") == ["drinks", "food"] + [f"t{i}" for i in range(8)]
    print("ok")


def main(media):
    manifest_path = media / "manifest.json"
    manifest = json.loads(manifest_path.read_text())
    caps = json.loads(CAPS.read_text())
    known = {str(m["id"]) for m in manifest["videos"]}
    added = 0
    for info_path in sorted(media.glob("*/*.info.json")):
        info = json.loads(info_path.read_text())
        vid, topic = str(info["id"]), info_path.parent.name
        if vid in known:
            continue
        if not info_path.with_name(f"{vid}.mp4").exists():
            print(f"skip {topic}/{vid}: no .mp4 next to the info.json (download with --remux-video mp4)")
            continue
        cap = caps.setdefault(vid, split_caption(info.get("description") or "", topic))
        manifest["videos"].append({"id": vid, "topic": topic, "query": "yt-dlp", "file": f"{topic}/{vid}.mp4",
                                   "width": info.get("width"), "height": info.get("height"),
                                   "duration": round(info.get("duration") or 0), "tags": ", ".join(cap["hashtags"]),
                                   "user": info.get("uploader"), "page": info.get("webpage_url")})
        added += 1
    # Edited entries keep their words, but every clip carries its topic and Explore tab tags.
    for m in manifest["videos"]:
        cap = caps[str(m["id"])]
        cap["hashtags"] = with_topic_tags(cap["hashtags"], m["topic"])
        m["tags"] = ", ".join(cap["hashtags"])
    manifest_path.write_text(json.dumps(manifest, indent=1, ensure_ascii=False))
    CAPS.write_text(json.dumps(caps, indent=1, ensure_ascii=False) + "\n")
    print(f"added {added} clips, manifest now has {len(manifest['videos'])}")


if __name__ == "__main__":
    if sys.argv[1:] == ["--check"]:
        check()
    elif len(sys.argv) == 2:
        main(Path(sys.argv[1]).expanduser())
    else:
        sys.exit(__doc__)
