"""The rules that have to mean the same thing here as they do in the Java service.

Each of these mirrors a constant or a branch in recommendation-service. If one of them is
changed on one side only, the model trains on a column that no longer describes what it is
served, and nothing else in the system will notice.
"""

from fastapi.testclient import TestClient

import app as rank_app
from features import (
    FEATURE_NAMES,
    NEUTRAL_QUALITY,
    affinity_delta,
    completion_rate,
    log_watches,
    to_row,
)


def test_completion_rate_needs_enough_watches():
    # One viewer finishing the only watch a video ever had is not a perfect video.
    assert completion_rate(watches=1, completions=1) == NEUTRAL_QUALITY
    assert completion_rate(watches=4, completions=4) == NEUTRAL_QUALITY
    assert completion_rate(watches=10, completions=5) == 0.5
    assert completion_rate(watches=10, completions=9) == 0.9


def test_a_skip_moves_affinity_down_not_nowhere():
    # Nobody presses "not interested", so scrolling past is the only negative signal there is.
    assert affinity_delta(watched_ms=1_000, duration_ms=10_000) == -0.5
    assert affinity_delta(watched_ms=5_000, duration_ms=10_000) == 0.5
    assert affinity_delta(watched_ms=10_000, duration_ms=10_000) == 1.0


def test_affinity_survives_a_zero_duration():
    assert affinity_delta(watched_ms=5_000, duration_ms=0) == -0.5


def test_a_rewatch_does_not_score_above_one():
    assert affinity_delta(watched_ms=30_000, duration_ms=10_000) == 1.0


def test_row_order_follows_feature_names():
    candidate = {
        "log_watches": 1.0,
        "completion_rate": 2.0,
        "age_hours": 3.0,
        "tag_affinity": 4.0,
        "tag_overlap": 5.0,
    }
    assert to_row(candidate) == [1.0, 2.0, 3.0, 4.0, 5.0]


def test_request_schema_carries_exactly_the_model_features():
    # A field renamed on one side and not the other must fail here rather than line up by
    # position and produce confident scores from the wrong inputs.
    fields = set(rank_app.Candidate.model_fields) - {"video_id"}
    assert fields == set(FEATURE_NAMES)


def test_log_watches_flattens_the_tail():
    assert log_watches(0) == 0.0
    assert log_watches(1_000_000) < 20


def test_rank_returns_no_scores_until_a_model_exists():
    # The caller falls back to its own ordering on an empty map, so a fresh checkout with no
    # trained model must answer 200 and empty rather than error.
    rank_app._model = None
    client = TestClient(rank_app.app)
    response = client.post("/rank", json={
        "user_id": 1,
        "candidates": [{
            "video_id": "v1",
            "log_watches": 1.0,
            "completion_rate": 0.5,
            "age_hours": 2.0,
            "tag_affinity": 0.0,
            "tag_overlap": 0,
        }],
    })
    assert response.status_code == 200
    assert response.json()["scores"] == {}
