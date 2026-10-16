"""/moderate/text with a stub scorer: ordering, blank texts, caps, and the switches.

The real weights are exercised once, by hand, in the built image (docs/moderation.md); these tests
need neither torch nor the model, like the frame tests.
"""

import pytest
from fastapi import HTTPException

import app


def scores(clean, offensive, hate):
    return {"CLEAN": clean, "OFFENSIVE": offensive, "HATE": hate}


class StubScorer:
    """Hateful when the text says "ghét", offensive when it says "vãi", clean otherwise."""

    def __init__(self):
        self.calls = []

    def __call__(self, texts):
        self.calls.append(list(texts))
        result = []
        for text in texts:
            if "ghét" in text:
                result.append([scores(0.05, 0.05, 0.90)])
            elif "vãi" in text:
                result.append([scores(0.05, 0.93, 0.02)])
            else:
                result.append([scores(0.98, 0.01, 0.01)])
        return result


@pytest.fixture
def scorer(monkeypatch):
    stub = StubScorer()
    monkeypatch.setattr(app, "_text_scorer", stub)
    monkeypatch.setattr(app, "TEXT_MODEL", "visolex/phobert-v2-hsd")
    return stub


def test_one_result_per_text_in_order(scorer):
    response = app.moderate_text(app.ModerateTextRequest(texts=["video hay", "ghét bọn này", "đẹp vãi"]))

    assert [result.verdict for result in response.results] == ["ALLOW", "HOLD", "ALLOW"]
    assert [result.label for result in response.results] == ["CLEAN", "HATE", "OFFENSIVE"]
    assert response.modelVersion == app.TEXT_MODEL_VERSION
    assert response.model == "visolex/phobert-v2-hsd"


def test_blank_texts_are_clean_without_asking_the_model(scorer):
    response = app.moderate_text(app.ModerateTextRequest(texts=["", "   ", "ghét"]))

    assert [result.verdict for result in response.results] == ["ALLOW", "ALLOW", "HOLD"]
    assert scorer.calls == [["ghét"]]


def test_nothing_to_read_never_reaches_the_model(scorer):
    response = app.moderate_text(app.ModerateTextRequest(texts=[]))

    assert response.results == []
    assert scorer.calls == []


def test_more_texts_than_the_cap_is_refused(scorer):
    with pytest.raises(HTTPException) as refused:
        app.moderate_text(app.ModerateTextRequest(texts=["a"] * (app.TEXT_MAX_TEXTS + 1)))
    assert refused.value.status_code == 400


def test_still_loading_is_a_503_the_caller_reads_as_unavailable(monkeypatch):
    monkeypatch.setattr(app, "_text_scorer", None)
    monkeypatch.setattr(app, "TEXT_MODEL", "visolex/phobert-v2-hsd")
    with pytest.raises(HTTPException) as unavailable:
        app.moderate_text(app.ModerateTextRequest(texts=["a"]))
    assert unavailable.value.status_code == 503


def test_switched_off_is_a_503_too(monkeypatch):
    monkeypatch.setattr(app, "TEXT_MODEL", "")
    with pytest.raises(HTTPException) as off:
        app.moderate_text(app.ModerateTextRequest(texts=["a"]))
    assert off.value.status_code == 503


def test_config_reports_the_text_model_for_the_console():
    config = app.config()

    assert config["textModel"] == app.TEXT_MODEL
    assert config["textModelVersion"] == app.TEXT_MODEL_VERSION
    assert config["textHateHoldAt"] == app.TEXT_THRESHOLDS.hate_hold_at
    assert config["textEnabled"] is bool(app.TEXT_MODEL)
