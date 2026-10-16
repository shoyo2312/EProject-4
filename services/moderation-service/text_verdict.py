"""The text decision rule, kept out of app.py and text_classifier.py so it is testable without torch.

Everything here is arithmetic over class probabilities. The model, the tokenizer and the HTTP layer
live elsewhere; this module only answers "given these numbers, what do we do with this text".
"""

from dataclasses import dataclass

ALLOW = "ALLOW"
HOLD = "HOLD"

CLEAN = "CLEAN"
OFFENSIVE = "OFFENSIVE"
HATE = "HATE"


@dataclass(frozen=True)
class TextThresholds:
    """Where a text stops being a matter for the rules alone.

    One number on purpose. Only HATE holds: ViHSD's OFFENSIVE is mostly profanity used as an
    intensifier ("đẹp vãi"), which the platform's word list leaves alone deliberately, and the
    profanity that is abuse the list already holds. OFFENSIVE still comes back as the label, so a
    caller can record it, and the day it should hold is a change here, not in every caller.
    """

    hate_hold_at: float = 0.90


@dataclass(frozen=True)
class TextDecision:
    verdict: str
    label: str
    score: float
    scores: dict


def decide_text(windows: list[dict], thresholds: TextThresholds) -> TextDecision:
    """One verdict for a text, from the class probabilities of each window it was cut into.

    A text is as bad as its worst window: an insult at the end of a long description is not diluted
    by the paragraphs before it. HOLD when any window's HATE reaches the threshold, reported with
    that window; otherwise ALLOW, reported with the most toxic window (lowest CLEAN) and its most
    likely label — which is what a caller records as a note and what the threshold gets tuned on.

    No windows means no text, and no text is clean.
    """
    if not windows:
        return TextDecision(ALLOW, CLEAN, 1.0, {CLEAN: 1.0, OFFENSIVE: 0.0, HATE: 0.0})

    hateful = max(windows, key=lambda probabilities: probabilities.get(HATE, 0.0))
    if hateful.get(HATE, 0.0) >= thresholds.hate_hold_at:
        return TextDecision(HOLD, HATE, hateful[HATE], _rounded(hateful))

    toxic = min(windows, key=lambda probabilities: probabilities.get(CLEAN, 0.0))
    label = max(toxic, key=toxic.get)
    return TextDecision(ALLOW, label, toxic[label], _rounded(toxic))


def windows(ids: list[int], size: int, stride: int, max_windows: int) -> list[list[int]]:
    """Cut a text's token ids into short overlapping pieces, at most ``max_windows`` of them.

    Short because of what the model learned from: ViHSD is comments of a few dozen tokens, and one
    hateful sentence after two hundred tokens of a food review scores as clean — measured, not
    guessed (docs/moderation.md). A piece the size of a comment reads like one. Overlapping, so a
    phrase cut at a boundary is whole in the next piece.

    Past the cap the rest is not read: the rules have read all of it already, and an unbounded
    number of pieces is an unbounded forward pass on a CPU shared by every caller. An empty text is
    one empty piece rather than none, so every text gets an answer.
    """
    if not ids:
        return [[]]
    pieces = []
    start = 0
    while True:
        pieces.append(ids[start:start + size])
        if start + size >= len(ids) or len(pieces) == max_windows:
            return pieces
        start += stride


def _rounded(probabilities: dict) -> dict:
    return {label: round(float(value), 4) for label, value in probabilities.items()}
