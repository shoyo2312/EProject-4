#!/usr/bin/env python3
"""Avatars and stories cut from the seed clips themselves, so no extra images are needed.

    python3 -I make-media.py <media-dir> [--avatars 220] [--stories 48]

Avatars: one square 512 px JPEG frame per clip (`avatars/<id>.jpg`), from a frame around 40 % in.
Stories: `stories/<id>.jpg` portrait 1080x1920 frames, plus clips of 8–45 s referenced as-is
(`<topic>/<id>.mp4`) for video stories, two thirds images, one third videos. Both are written into
manifest.json (`avatars`, `stories`), replacing whatever was there. Re-runnable: existing files are kept.
"""
import argparse, json, random, subprocess
from pathlib import Path


def frame(src, dst, at, vf):
    if dst.exists():
        return True
    r = subprocess.run(["ffmpeg", "-v", "error", "-y", "-ss", f"{at:.2f}", "-i", str(src), "-frames:v", "1", "-vf", vf, "-q:v", "3", str(dst)])
    return r.returncode == 0 and dst.exists()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("media_dir")
    ap.add_argument("--avatars", type=int, default=220)
    ap.add_argument("--stories", type=int, default=48)
    a = ap.parse_args()
    media = Path(a.media_dir).expanduser()
    manifest = json.loads((media / "manifest.json").read_text())
    rnd = random.Random(7)
    clips = sorted(manifest["videos"], key=lambda v: v["id"])
    rnd.shuffle(clips)
    (media / "avatars").mkdir(exist_ok=True)
    (media / "stories").mkdir(exist_ok=True)
    avatars = []
    for v in clips:
        if len(avatars) >= a.avatars:
            break
        dst = media / "avatars" / f"{v['id']}.jpg"
        if frame(media / v["file"], dst, v["duration"] * 0.4, "crop='min(iw,ih)':'min(iw,ih)',scale=512:512"):
            avatars.append({"file": f"avatars/{v['id']}.jpg", "id": v["id"]})
    stories = []
    n_video = a.stories // 3
    short = [v for v in clips if 8 <= v["duration"] <= 45 and v["height"] >= v["width"]]
    for v in short[:n_video]:
        stories.append({"file": v["file"], "id": v["id"], "type": "video"})
    for v in reversed(clips):
        if len(stories) >= a.stories:
            break
        dst = media / "stories" / f"{v['id']}.jpg"
        if frame(media / v["file"], dst, v["duration"] * 0.6, "scale=1080:1920:force_original_aspect_ratio=increase,crop=1080:1920"):
            stories.append({"file": f"stories/{v['id']}.jpg", "id": v["id"], "type": "image"})
    manifest["avatars"], manifest["stories"] = avatars, stories
    (media / "manifest.json").write_text(json.dumps(manifest, indent=1, ensure_ascii=False))
    print(f"avatars {len(avatars)}, stories {len(stories)} ({n_video} video)")


if __name__ == "__main__":
    main()
