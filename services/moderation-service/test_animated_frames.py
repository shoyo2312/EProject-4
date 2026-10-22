"""An animated image is scored across its frames, not on the first one alone.

A story image arrives as the uploaded file itself. Opening it and converting to RGB reads frame 0,
so an animated WebP, APNG or GIF with a clean first frame was approved whatever came after it —
and the upload pins a content type, not the bytes, so a GIF sent as image/png got the same pass.
"""

import base64
import io

import pytest
from PIL import Image

import app

WHITE, RED, DARK_RED = (255, 255, 255), (255, 0, 0), (225, 0, 0)


def animated(fmt: str, colours: list[tuple[int, int, int]]) -> str:
    # Every frame a different colour: identical neighbours are merged into one frame on save.
    frames = [Image.new("RGB", (8, 8), colour) for colour in colours]
    buffer = io.BytesIO()
    frames[0].save(buffer, format=fmt, save_all=True, append_images=frames[1:], duration=100, loop=0)
    return base64.b64encode(buffer.getvalue()).decode()


def flags_red(images, top_k=None):
    """NSFW wherever a frame is red, so the verdict shows which frames were looked at."""
    def score(image):
        r, g, _ = image.getpixel((4, 4))
        return 0.95 if r > 200 and g < 80 else 0.01
    return [[{"label": "nsfw", "score": score(image)}] for image in images]


@pytest.mark.parametrize("fmt", ["WEBP", "PNG", "GIF"])
def test_a_clean_first_frame_does_not_clear_the_rest(monkeypatch, fmt):
    monkeypatch.setattr(app, "_classifier", flags_red)

    response = app.moderate(app.ModerateRequest(frames=[animated(fmt, [WHITE, RED, DARK_RED])]))

    assert response.totalFrames == 3
    assert response.verdict == "REJECTED"


def test_a_long_animation_is_sampled_through_to_its_last_frame(monkeypatch):
    monkeypatch.setattr(app, "_classifier", flags_red)
    greys = [(200 + i, 200 + i, 200 + i) for i in range(39)]

    response = app.moderate(app.ModerateRequest(frames=[animated("GIF", greys + [RED])]))

    assert response.totalFrames == app.ANIMATION_FRAMES
    assert response.verdict == "REVIEW"
