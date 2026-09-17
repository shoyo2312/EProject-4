"""The Java side of the feature contract must agree with features.py.

features.py is the single definition of what a feature means, but serving is Java: FeedServiceImpl
and RecommendationServiceImpl carry the same numbers again because they are the code that actually
computes the features at request time. Nothing makes the two move together, and a disagreement has
no symptom — the model still scores, /rank still answers 200, and the feed is quietly worse than
the heuristic it replaced. This is the check that turns that into a test failure.

Reading the constants out of the Java rather than restating them here is the whole point: a copy
in this file would be a third place to forget.
"""

import re
from pathlib import Path

import pytest

import features

SERVICE = (Path(__file__).resolve().parents[1] / "recommendation-service" / "src" / "main"
           / "java" / "com" / "tiktok" / "recommendationservice" / "service")

# Each Java constant and the features.py name it has to equal.
CONTRACT = [
    ("FeedServiceImpl.java", "TOP_TAGS", "TOP_TAGS"),
    ("FeedServiceImpl.java", "PER_TAG", "PER_TAG"),
    ("FeedServiceImpl.java", "MIN_WATCHES_FOR_QUALITY", "MIN_WATCHES_FOR_QUALITY"),
    ("FeedServiceImpl.java", "NEUTRAL_QUALITY", "NEUTRAL_QUALITY"),
    ("FeedServiceImpl.java", "UNKNOWN_AGE_HOURS", "UNKNOWN_AGE_HOURS"),
    ("RecommendationServiceImpl.java", "SKIP_RATIO", "SKIP_RATIO"),
    ("RecommendationServiceImpl.java", "SKIP_PENALTY", "SKIP_PENALTY"),
]


def java_constant(file_name: str, name: str) -> float:
    """The literal assigned to a `private static final` field, as a number.

    Deliberately a regex over the source and not a parser: the fields are plain numeric literals
    on one line, and a build step that compiles Java to check five numbers would cost more than
    the drift it prevents. A field that stops matching fails loudly rather than being skipped —
    a renamed constant is exactly the drift this exists to catch.
    """
    source = (SERVICE / file_name).read_text()
    match = re.search(
        rf"private static final \w+ {name}\s*=\s*(-?[\d.]+)\s*;", source)
    if match is None:
        pytest.fail(f"{file_name} no longer declares a constant named {name}")
    return float(match.group(1))


@pytest.mark.skipif(not SERVICE.is_dir(), reason="recommendation-service source is not mounted")
@pytest.mark.parametrize("file_name,java_name,python_name", CONTRACT)
def test_java_serving_constant_matches_features_py(file_name, java_name, python_name):
    assert java_constant(file_name, java_name) == float(getattr(features, python_name)), (
        f"{file_name}:{java_name} and features.py:{python_name} disagree — the online feature and "
        f"the trained one no longer mean the same thing"
    )


def test_the_contract_covers_every_shared_constant():
    """A new shared number added to features.py has to gain a row above, or it drifts unwatched."""
    shared = {"TOP_TAGS", "PER_TAG", "MIN_WATCHES_FOR_QUALITY", "NEUTRAL_QUALITY",
              "UNKNOWN_AGE_HOURS", "SKIP_RATIO", "SKIP_PENALTY"}
    declared = {name for name in vars(features) if name.isupper() and name != "FEATURE_NAMES"}
    assert declared == shared, (
        "features.py's shared constants changed; add the new one to CONTRACT above with the Java "
        "field it must equal, or this check stops covering it"
    )
