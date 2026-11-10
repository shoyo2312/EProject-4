"""Push each clip's hashtags from captions.json into the running stores: video-service's Mongo
document, search-service's Elasticsearch document, and the Redis video cache (evicted). Idempotent.
Use after editing hashtags of an already-seeded clip (e.g. after adding Explore tab tags).

Usage: retag.py <media-dir>
"""
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from backdate import es_bulk, mongosh, redis  # noqa: E402

CAPS = Path(__file__).with_name("captions.json")


def main(media):
    manifest = json.loads((media / "manifest.json").read_text())
    caps = json.loads(CAPS.read_text())
    ledger = json.loads((media / "ledger.json").read_text())
    ops, bulk, keys = [], [], []
    for m in manifest["videos"]:
        entry = ledger["videos"].get(f'{m["topic"]}/{m["id"]}')  # written by seed.py's videos phase
        if not entry:
            continue
        vid = entry["id"]
        tags = caps[str(m["id"])]["hashtags"]
        entry["tags"] = tags
        ops.append(f'{{updateOne: {{filter: {{_id: "{vid}"}}, update: {{$set: {{tags: {json.dumps(tags)}}}}}}}}}')
        bulk.append(json.dumps({"update": {"_index": "videos", "_id": str(vid)}}))
        bulk.append(json.dumps({"doc": {"tags": tags}}))
        keys.append(f"DEL video:{vid}")
    mongosh("video_db", f"db.videos.bulkWrite([{','.join(ops)}], {{ordered: false}});")
    es_bulk("\n".join(bulk) + "\n")
    redis(keys)
    (media / "ledger.json").write_text(json.dumps(ledger, indent=1, ensure_ascii=False))
    print(f"retagged {len(ops)} videos")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    main(Path(sys.argv[1]).expanduser())
