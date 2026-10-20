"""The zero-shot harm scorer as /moderate sees it: it holds videos back and never removes one.

The scorer itself is replaced by a dictionary of scores — what is pinned here is what app.py does
with them, which is where a mistake would publish a video (Azure overruling a drugs REVIEW) or
remove one (a harm label reaching REJECTED).
"""

import base64
import io

from PIL import Image

import app
import escalation
import harm_classifier
from verdict import APPROVED, REJECTED, REVIEW, decide, review_only


def frame() -> str:
    buffer = io.BytesIO()
    Image.new("RGB", (8, 8), "white").save(buffer, format="JPEG")
    return base64.b64encode(buffer.getvalue()).decode()


def nsfw_at(score):
    def classifier(images, top_k=None):
        return [[{"label": "nsfw", "score": score}, {"label": "normal", "score": 1 - score}] for _ in images]
    return classifier


def harm_at(**worst):
    """Every category at 0.01 on every frame, except the ones given, which hit that score on frame 0."""
    def scorer(images):
        return {category: [worst.get(category, 0.01)] + [0.01] * (len(images) - 1)
                for category in harm_classifier.CATEGORIES}
    return scorer


def moderate(monkeypatch, nsfw=0.01, escalate_to=None, **harm):
    monkeypatch.setattr(app, "_classifier", nsfw_at(nsfw))
    monkeypatch.setattr(app, "_harm_scorer", harm_at(**harm))
    asked = []
    monkeypatch.setattr(escalation, "enabled", lambda: escalate_to is not None)

    def second_opinion(frames):
        asked.append(frames)
        return escalation.SecondOpinion(escalate_to, "azure-test")
    monkeypatch.setattr(escalation, "second_opinion", second_opinion)
    return app.moderate(app.ModerateRequest(frames=[frame()] * 10)), asked


def test_review_only_never_rejects_even_past_one():
    # A sum of softmax probabilities can come out a hair over 1.0.
    decision = decide([1.0000001, 1.0, 0.99], review_only(0.55))
    assert decision.verdict == REVIEW
    assert decide([0.54, 0.2], review_only(0.55)).verdict == APPROVED


def test_one_harmful_frame_sends_the_video_to_a_person(monkeypatch):
    response, _ = moderate(monkeypatch, violence=0.89)

    assert response.verdict == REVIEW
    assert response.label == "violence"
    assert response.maxScore == 0.89
    assert response.model == app.HARM_MODEL


def test_certain_harm_is_still_only_review(monkeypatch):
    response, _ = moderate(monkeypatch, gore=0.99)

    assert response.verdict == REVIEW


def test_clean_video_is_approved_by_both(monkeypatch):
    response, _ = moderate(monkeypatch)

    assert response.verdict == APPROVED
    assert response.modelVersion == f"{app.MODEL_VERSION}+{app.HARM_MODEL_VERSION}"


def test_nsfw_rejection_outranks_a_harm_review(monkeypatch):
    response, _ = moderate(monkeypatch, nsfw=0.99, violence=0.95)

    assert response.verdict == REJECTED
    assert response.label == "nsfw"
    assert response.model == app.MODEL


def test_azure_never_overrules_a_label_it_cannot_judge(monkeypatch):
    # Azure has no drugs category: asked, it would answer severity 0 and publish the video.
    response, asked = moderate(monkeypatch, escalate_to=APPROVED, drugs=0.71)

    assert asked == []
    assert response.verdict == REVIEW
    assert response.label == "drugs"


def test_azure_is_not_asked_when_nsfw_and_harm_both_hold_the_video(monkeypatch):
    # An approval would clear the NSFW doubt and publish the gambling video with it.
    response, asked = moderate(monkeypatch, nsfw=0.7, escalate_to=APPROVED, gambling=0.9)

    assert asked == []
    assert response.verdict == REVIEW


def test_azure_still_settles_an_nsfw_only_review(monkeypatch):
    response, asked = moderate(monkeypatch, nsfw=0.7, escalate_to=APPROVED)

    assert len(asked) == 1
    assert response.verdict == APPROVED
    assert response.modelVersion.endswith("+azure-test")


def test_switched_off_is_the_nsfw_check_alone(monkeypatch):
    monkeypatch.setattr(app, "_classifier", nsfw_at(0.01))
    monkeypatch.setattr(app, "_harm_scorer", None)

    response = app.moderate(app.ModerateRequest(frames=[frame()] * 10))

    assert response.verdict == APPROVED
    assert response.label == "nsfw"
    assert response.modelVersion == app.MODEL_VERSION


def test_category_scores_sum_each_categorys_descriptions():
    owners = ["violence", "violence", "drugs", "benign"]
    scores = harm_classifier.category_scores([[0.3, 0.3, 0.1, 0.3], [0.0, 0.0, 0.9, 0.1]], owners)

    assert scores["violence"] == [0.6, 0.0]
    assert scores["drugs"] == [0.1, 0.9]
    assert "benign" not in scores
    assert scores["gambling"] == [0.0, 0.0]


def test_every_category_has_descriptions_and_none_is_repeated():
    texts, owners = harm_classifier.prompts()

    assert len(texts) == len(set(texts))
    assert set(owners) == {*harm_classifier.CATEGORIES, "benign"}
    assert all(harm_classifier.CATEGORIES.values())


def test_cigarettes_and_vapes_are_not_drugs():
    # A product decision, not a tuning detail: smoking and vaping are legal, and without a benign
    # description of their own they drift into "drugs".
    assert "a photo of a person smoking a cigarette" in harm_classifier.BENIGN
    assert "a photo of a person vaping an e-cigarette" in harm_classifier.BENIGN
    assert not any("vap" in text or "cigarette" in text for text in harm_classifier.CATEGORIES["drugs"])


def test_config_shows_the_harm_scorer():
    config = app.config()

    assert config["harmEnabled"] is True
    assert config["harmModelVersion"] == app.HARM_MODEL_VERSION
    assert config["harmReviewAt"] == app.HARM_THRESHOLDS.review_at
    assert config["harmLabels"] == ["violence", "gore", "drugs", "accident", "gambling"]
