"""Score a file of comments against the running moderation-service's text classifier.

What this is for: measuring the classifier's precision on this platform's own text before
TEXT_CLASSIFIER_ENFORCE is switched on, and re-checking it whenever the threshold or the weights
change. The numbers on the model card come from ViHSD; Vietnamese comments under short videos are
not ViHSD, and the only way to know how far apart they are is to run text you have judged yourself.

One text per line. Start a line with "+ " for a text that should be held and "- " for one that
should not; unmarked lines are scored and printed but not counted.

    python score_texts.py ~/samples/comments.txt
    python score_texts.py ~/samples/comments.txt --url http://127.0.0.1:8099

Uses nothing outside the standard library, so it runs from any checkout.
"""

import argparse
import json
import pathlib
import sys
import urllib.error
import urllib.request

BATCH = 16


def post(url: str, texts: list[str]) -> list[dict]:
    body = json.dumps({"texts": texts}).encode()
    request = urllib.request.Request(f"{url}/moderate/text", data=body,
                                     headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response)["results"]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("file", type=pathlib.Path)
    parser.add_argument("--url", default="http://127.0.0.1:8099")
    args = parser.parse_args()

    lines = [line.rstrip("\n") for line in args.file.read_text(encoding="utf-8").splitlines() if line.strip()]
    expected: list[bool | None] = []
    texts: list[str] = []
    for line in lines:
        if line.startswith("+ "):
            expected.append(True)
            texts.append(line[2:])
        elif line.startswith("- "):
            expected.append(False)
            texts.append(line[2:])
        else:
            expected.append(None)
            texts.append(line)

    results: list[dict] = []
    try:
        for start in range(0, len(texts), BATCH):
            results.extend(post(args.url, texts[start:start + BATCH]))
    except urllib.error.URLError as error:
        print(f"Could not reach {args.url}: {error}", file=sys.stderr)
        return 1

    held_right = held_wrong = missed = 0
    for text, want, result in zip(texts, expected, results):
        held = result["verdict"] == "HOLD"
        mark = " " if want is None else ("ok" if held == want else "XX")
        print(f"{mark:2} {result['verdict']:5} {result['label']:9} {result['score']:.3f}  {text}")
        if want is True and held:
            held_right += 1
        elif want is False and held:
            held_wrong += 1
        elif want is True and not held:
            missed += 1

    judged_held = held_right + held_wrong
    if judged_held or missed:
        precision = held_right / judged_held if judged_held else float("nan")
        recall = held_right / (held_right + missed) if held_right + missed else float("nan")
        print(f"\nheld {judged_held} marked texts: precision {precision:.2f}, recall {recall:.2f}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
