"""More frames than the cap is trimmed, not refused.

media-worker decides how many frames to send from its own environment variable, in its own
container. When going over meant a 400, raising that one past MODERATION_MAX_FRAMES turned every
upload on the platform into three failed attempts and a REVIEW verdict — the fail-safe path doing
exactly what it is supposed to, for a reason neither service's log named.
"""

import base64
import io

from PIL import Image

import app


def frame() -> str:
    buffer = io.BytesIO()
    Image.new("RGB", (8, 8), "white").save(buffer, format="JPEG")
    return base64.b64encode(buffer.getvalue()).decode()


def clean_classifier(images, top_k=None):
    return [[{"label": "nsfw", "score": 0.01}, {"label": "normal", "score": 0.99}] for _ in images]


def test_more_frames_than_the_cap_are_trimmed(monkeypatch):
    monkeypatch.setattr(app, "_classifier", clean_classifier)

    response = app.moderate(app.ModerateRequest(frames=[frame()] * (app.MAX_FRAMES + 8)))

    assert response.totalFrames == app.MAX_FRAMES
    assert response.verdict == "APPROVED"


def test_a_normal_batch_is_untouched(monkeypatch):
    monkeypatch.setattr(app, "_classifier", clean_classifier)

    response = app.moderate(app.ModerateRequest(frames=[frame()] * 10))

    assert response.totalFrames == 10
