"""The feature contract, shared by training and serving.

This module exists so there is exactly one definition of what a feature is called and in what
order the model sees them. Ranking models fail quietly: swap two columns between training and
serving and the model still returns confident-looking floats, the API still returns 200, and
the only symptom is a feed that is subtly worse than the heuristic it replaced. Nothing here
should be duplicated in app.py or train.py — both import from here.
"""

from __future__ import annotations

import math

# Order is load-bearing: LightGBM sees a positional matrix, not a dict.
FEATURE_NAMES = [
    "log_watches",
    "completion_rate",
    "age_hours",
    "tag_affinity",
    "tag_overlap",
]

# The completion rate given to a video with too few watches to have a meaningful one. Must match
# MIN_WATCHES_FOR_QUALITY / NEUTRAL_QUALITY in FeedServiceImpl.
MIN_WATCHES_FOR_QUALITY = 5
NEUTRAL_QUALITY = 0.5

# A watch under this fraction is a skip, and moves tag affinity down instead of up. Must match
# SKIP_RATIO / SKIP_PENALTY in RecommendationServiceImpl — this is the rule the online profile
# is built with, so training has to reproduce it or the tag_affinity column means two different
# things on the two sides.
SKIP_RATIO = 0.2
SKIP_PENALTY = -0.5

# How many of a viewer's tags steer the feed. Must match TOP_TAGS in FeedServiceImpl: serving
# reads the viewer's tag profile with ZREVRANGE 0..TOP_TAGS-1 and keeps only the positive ones,
# so tag_affinity and tag_overlap there are sums over at most this many tags. Training that
# reconstructs them over every tag a viewer ever touched computes a different number under the
# same column name — the model then learns from a feature it is never served.
TOP_TAGS = 5


def completion_rate(watches: float, completions: float) -> float:
    """One viewer finishing the only watch a video ever had is not a 100% completion rate."""
    if watches < MIN_WATCHES_FOR_QUALITY:
        return NEUTRAL_QUALITY
    return completions / watches


def affinity_delta(watched_ms: float, duration_ms: float) -> float:
    """How much one watch moves the viewer's affinity for each of that video's tags."""
    ratio = 0.0 if duration_ms <= 0 else min(1.0, watched_ms / duration_ms)
    return SKIP_PENALTY if ratio < SKIP_RATIO else ratio


def to_row(candidate: dict) -> list[float]:
    """One candidate to one positional feature row, in FEATURE_NAMES order."""
    return [float(candidate[name]) for name in FEATURE_NAMES]


def log_watches(watches: float) -> float:
    """Popularity is long-tailed; the raw count would let one viral video own every split."""
    return math.log1p(watches)
