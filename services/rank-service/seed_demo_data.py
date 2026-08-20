"""Fills ClickHouse with a synthetic watch log so the trainer has something to chew on.

A fresh install has no history, and a ranking model cannot be demonstrated — or even smoke
tested — without one. This is for local demos and for verifying that train.py runs end to end;
it writes into the same tables analytics-service writes into, so run it on a throwaway database,
never on anything holding real events.

The generated preference is deliberately simple and deliberately *not* one of the features: each
user likes two tags, and finishes tagged videos they like far more often than ones they do not.
A model that cannot find that through tag_affinity has a bug.
"""

from __future__ import annotations

import random
import uuid
from datetime import datetime, timedelta, timezone

from train import connect

TAGS = ["dance", "food", "cat", "code", "travel", "music"]
USERS = 60
VIDEOS = 200
DAYS = 6
WATCHES = 12_000


def main() -> None:
    random.seed(7)
    client = connect()
    now = datetime.now(timezone.utc).replace(tzinfo=None, microsecond=0)

    video_tags, published = [], {}
    for index in range(VIDEOS):
        video_id = f"demo-v{index}"
        published[video_id] = now - timedelta(hours=random.uniform(0, DAYS * 24))
        for tag in random.sample(TAGS, random.randint(1, 2)):
            video_tags.append([video_id, tag, published[video_id]])

    tags_by_video: dict[str, set[str]] = {}
    for video_id, tag, _ in video_tags:
        tags_by_video.setdefault(video_id, set()).add(tag)

    liked = {user: set(random.sample(TAGS, 2)) for user in range(1, USERS + 1)}

    watches = []
    for _ in range(WATCHES):
        user = random.randint(1, USERS)
        video_id = f"demo-v{random.randrange(VIDEOS)}"
        occurred = published[video_id] + timedelta(hours=random.uniform(0, 24 * DAYS))
        if occurred > now:
            continue
        match = bool(tags_by_video.get(video_id, set()) & liked[user])
        duration = random.choice([8_000, 15_000, 30_000])
        ratio = random.betavariate(5, 2) if match else random.betavariate(1.5, 5)
        watched = int(duration * min(1.0, ratio))
        watches.append([
            str(uuid.uuid4()), video_id, user, watched, duration, 1 if ratio >= 0.9 else 0, occurred,
        ])

    client.insert("video_tags", video_tags, column_names=["video_id", "tag", "published_at"])
    client.insert(
        "watch_events",
        watches,
        column_names=[
            "event_id", "video_id", "user_id", "watched_ms", "duration_ms", "completed", "occurred_at",
        ],
    )
    print(f"inserted {len(watches)} watches over {len(video_tags)} video-tag rows")


if __name__ == "__main__":
    main()
