"""Score a folder of images or videos against the running moderation-service.

What this is for: picking thresholds. The defaults in app.py are opening guesses, and the only
way to improve on a guess is to run content you have judged yourself through the classifier and
see where its numbers fall relative to your judgement.

Sample the borderline, not the obvious. Explicit content scores near 1.0 and tells you nothing
you did not already assume; swimwear, gym footage, breastfeeding, classical nudes and cosplay
score anywhere from 0.2 to 0.95, and that spread is what your thresholds actually have to live
with. A folder of only-clean and only-explicit samples will make any threshold look perfect.

    python score_samples.py ~/samples/borderline
    python score_samples.py ~/samples/clean --url http://127.0.0.1:8099

Videos are sampled the way media-worker samples them -- frames spread evenly across the whole
file -- so the numbers here are the numbers the pipeline would produce. It needs ffmpeg on PATH
for that; images need nothing.
"""

import argparse
import base64
import json
import pathlib
import shutil
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request

IMAGES = {".jpg", ".jpeg", ".png", ".webp", ".bmp"}
VIDEOS = {".mp4", ".mov", ".webm", ".mkv", ".avi"}


def video_frames(path: pathlib.Path, count: int, work: pathlib.Path) -> list[bytes]:
    """The same even spread as Ffmpeg.sampleFrames, so the scores match the pipeline's."""
    duration = 1.0
    try:
        probed = subprocess.run(
            ["ffprobe", "-v", "error", "-show_entries", "format=duration",
             "-of", "default=nw=1:nk=1", str(path)],
            capture_output=True, text=True, check=True)
        duration = max(1.0, float(probed.stdout.strip()))
    except (subprocess.CalledProcessError, ValueError, FileNotFoundError):
        pass

    interval = duration / count
    subprocess.run(
        ["ffmpeg", "-nostdin", "-y", "-loglevel", "error",
         "-ss", f"{interval / 2:.6f}", "-i", str(path),
         "-vf", f"fps={1 / interval:.6f},scale=-2:224",
         "-frames:v", str(count), "-q:v", "5", "-f", "image2",
         str(work / "frame-%03d.jpg")],
        check=False)
    return [frame.read_bytes() for frame in sorted(work.glob("frame-*.jpg"))]


def score(url: str, frames: list[bytes]) -> dict:
    body = json.dumps({"frames": [base64.b64encode(f).decode() for f in frames]}).encode()
    request = urllib.request.Request(
        url.rstrip("/") + "/moderate", data=body, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=120) as response:
        return json.loads(response.read())


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("folder", type=pathlib.Path)
    parser.add_argument("--url", default="http://127.0.0.1:8099")
    parser.add_argument("--frames", type=int, default=10)
    arguments = parser.parse_args()

    files = sorted(p for p in arguments.folder.rglob("*")
                   if p.suffix.lower() in IMAGES | VIDEOS)
    if not files:
        print(f"No images or videos under {arguments.folder}", file=sys.stderr)
        return 1

    print(f"{'file':<44} {'verdict':<9} {'max':>6} {'susp':>5} {'frames':>7}")
    print("-" * 76)

    rows = []
    for path in files:
        try:
            if path.suffix.lower() in VIDEOS:
                with tempfile.TemporaryDirectory() as temporary:
                    frames = video_frames(path, arguments.frames, pathlib.Path(temporary))
            else:
                frames = [path.read_bytes()]
            if not frames:
                print(f"{path.name[:43]:<44} {'no frames':<9}")
                continue
            result = score(arguments.url, frames)
        except (urllib.error.URLError, OSError) as error:
            print(f"{path.name[:43]:<44} error: {error}", file=sys.stderr)
            continue

        rows.append(result)
        print(f"{path.name[:43]:<44} {result['verdict']:<9} "
              f"{result['maxScore']:>6.3f} {result['suspiciousFrames']:>5} {result['totalFrames']:>7}")

    if rows:
        print("-" * 76)
        for verdict in ("APPROVED", "REVIEW", "REJECTED"):
            n = sum(1 for r in rows if r["verdict"] == verdict)
            print(f"{verdict:<9} {n:>3}/{len(rows)}")
        # The number to move a threshold with. If your clean folder's highest score sits well
        # under the review line, the line has room to come down; if it crosses it, coming down
        # costs you false positives on content you already called fine.
        print(f"\nhighest score seen: {max(r['maxScore'] for r in rows):.3f}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
