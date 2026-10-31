"""The decision rule, kept out of app.py so it can be tested without loading a model.

Everything here is pure arithmetic over per-frame scores. The model, the HTTP layer and the
thresholds live elsewhere; this module only answers "given these numbers, what do we do".
"""

import math
from dataclasses import dataclass

APPROVED = "APPROVED"
REVIEW = "REVIEW"
REJECTED = "REJECTED"


@dataclass(frozen=True)
class Thresholds:
    """Where the two cut points sit, and how much corroboration a rejection needs.

    ``min_reject_frames`` is the whole reason this is not a single number. A classifier run over
    ten frames of an ordinary video will occasionally put one frame over 0.9 — a skin-toned
    close-up, a bad crop, a frame caught mid-cut. Rejecting on that one frame removes real
    videos; requiring a second frame to agree costs almost nothing on genuinely explicit content,
    which is rarely explicit in exactly one sampled frame out of ten.
    """

    review_at: float = 0.60
    reject_at: float = 0.90
    min_reject_frames: int = 2
    # The same guard for REVIEW, off by default: one frame over review_at is enough. Above 1, fewer
    # frames over review_at than this approve the video, unless one of them is at review_alone_at.
    min_review_frames: int = 1
    review_alone_at: float = 0.0


def review_only(review_at: float, min_frames: int = 1, alone_at: float = 0.0) -> Thresholds:
    """Thresholds that can send a video to a person but never remove it.

    For the zero-shot categories (harm_classifier): an action film, a boxing match and a kitchen
    knife are all a description away from "violence", and a model that cannot tell them apart
    does not get to take a video down on its own. No score reaches infinity, so the reject branch
    in decide() is unreachable — a sum of probabilities can land a hair over 1.0, so 1.0 cannot.
    """
    return Thresholds(review_at=review_at, reject_at=math.inf, min_reject_frames=1,
                      min_review_frames=min_frames, review_alone_at=alone_at)


@dataclass(frozen=True)
class Decision:
    verdict: str
    max_score: float
    suspicious_frames: int


def decide(scores: list[float], thresholds: Thresholds) -> Decision:
    """Turn per-frame scores for one label into a verdict.

    No frames means no evidence, and no evidence is not an approval: an empty list is sent to a
    human rather than published. The caller normally avoids this by not calling at all when it
    could not sample anything, but a video that yields zero decodable frames is exactly the kind
    of file worth a second look.
    """
    if not scores:
        return Decision(REVIEW, 0.0, 0)

    max_score = max(scores)
    suspicious = sum(1 for score in scores if score >= thresholds.review_at)
    # Capped at the frames there are: an image story is one frame, and waiting for a second that
    # cannot exist approved everything under review_alone_at.
    min_review_frames = min(thresholds.min_review_frames, len(scores))

    if max_score < thresholds.review_at:
        return Decision(APPROVED, max_score, suspicious)
    if max_score >= thresholds.reject_at and suspicious >= thresholds.min_reject_frames:
        return Decision(REJECTED, max_score, suspicious)
    if suspicious < min_review_frames and max_score < thresholds.review_alone_at:
        return Decision(APPROVED, max_score, suspicious)
    return Decision(REVIEW, max_score, suspicious)


def decide_together(scores: dict[str, list[float]], thresholds: Thresholds) -> tuple[str, Decision]:
    """Several labels from one model, decided as one detector: each frame counts by its worst label.

    CLIP splits one scene between neighbouring labels — a syringe on a table scored gambling on one
    frame and drugs on the next — so frames agreeing is counted across labels, not within one. The
    label reported is the one that scored the worst frame.
    """
    worst_per_frame = [max(frame) for frame in zip(*scores.values())]
    label = max(scores, key=lambda name: max(scores[name], default=0.0))
    return label, decide(worst_per_frame, thresholds)


def worst(decisions: dict[str, Decision]) -> tuple[str, Decision]:
    """The label whose decision is the most severe, which is what the video's verdict becomes.

    With one detector this is the only entry. It exists so adding a second — violence, weapons —
    is a new scorer in app.py and nothing else: the severity ordering and the tie-break on score
    are already here.
    """
    severity = {APPROVED: 0, REVIEW: 1, REJECTED: 2}
    return max(decisions.items(), key=lambda item: (severity[item[1].verdict], item[1].max_score))
