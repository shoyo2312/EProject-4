"""A frame's size is checked from its header, before anything is decoded.

A story image reaches /moderate as the uploaded file itself, capped in bytes but not in pixels, and a
single-colour PNG of a few hundred kilobytes can declare 13000x13000: over half a gigabyte decoded,
in a container already holding 2 GB of models under a 3 GB limit. The kill takes every call in
flight with it.

These files declare a size but carry almost no pixel data, so building and refusing them costs
nothing. The decoder refuses them too, as truncated, but only after allocating the canvas. A
refusal that names the size is what shows the decoder never ran.
"""

import base64
import io
import struct
import zlib

import pytest
from fastapi import HTTPException
from PIL import Image

import app

# 100M pixels is past Pillow's warning line, which is the point of two of these.
pytestmark = pytest.mark.filterwarnings("ignore::PIL.Image.DecompressionBombWarning")


def clean_classifier(images, top_k=None):
    return [[{"label": "nsfw", "score": 0.01}, {"label": "normal", "score": 0.99}] for _ in images]


def png_declaring(width: int, height: int) -> str:
    """An 8x8 PNG whose header claims width x height."""
    buffer = io.BytesIO()
    Image.new("RGB", (8, 8), "white").save(buffer, format="PNG")
    png = bytearray(buffer.getvalue())
    # IHDR comes first after the 8-byte signature: length, type, width, height, ..., then its CRC.
    png[16:24] = struct.pack(">II", width, height)
    png[29:33] = struct.pack(">I", zlib.crc32(png[12:29]))
    return base64.b64encode(png).decode()


def gif_declaring(sizes: list[tuple[int, int]]) -> str:
    """A GIF with an 8x8 canvas and a frame per (width, height), each holding one pixel of data."""
    gif = b"GIF89a" + struct.pack("<HHBBB", 8, 8, 0x80, 0, 0) + bytes(6)  # two-colour palette
    for width, height in sizes:
        # The frame's descriptor, then LZW with a minimum code size of 2: clear, colour 0, end.
        gif += b"," + struct.pack("<HHHHB", 0, 0, width, height, 0) + b"\x02\x02\x44\x01\x00"
    return base64.b64encode(gif + b";").decode()


def refusal(monkeypatch, frame: str) -> HTTPException:
    monkeypatch.setattr(app, "_classifier", clean_classifier)
    with pytest.raises(HTTPException) as refused:
        app.moderate(app.ModerateRequest(frames=[frame]))
    assert refused.value.status_code == 400
    return refused.value


def test_a_frame_over_the_cap_is_refused_from_its_header(monkeypatch):
    # 100M: Pillow only warns below about 179M, and would go on to decode it.
    assert "10000x10000" in refusal(monkeypatch, png_declaring(10000, 10000)).detail


def test_a_frame_past_pillows_own_limit_is_a_400_not_a_500(monkeypatch):
    # 400M: Image.open raises DecompressionBombError itself, before returning anything to check.
    refusal(monkeypatch, png_declaring(20000, 20000))


def test_a_fifty_megapixel_phone_photo_gets_past_the_cap(monkeypatch):
    # 8160x6144 reaches the decoder, which is as far as a file with no pixels can go.
    assert "not a decodable image" in refusal(monkeypatch, png_declaring(8160, 6144)).detail


def test_a_gif_frame_that_grows_the_canvas_is_refused_before_it_is_decoded(monkeypatch):
    # A GIF frame may reach past the canvas, which grows it, and seeking past a frame decodes it.
    # Of 31 frames every other one is sampled, so this is one the sampling only seeks past.
    frames = [(1, 1), (10000, 10000)] + [(1, 1)] * 29
    assert "10000x10000" in refusal(monkeypatch, gif_declaring(frames)).detail


def test_an_ico_is_refused(monkeypatch):
    # Opening an ICO decodes the image inside it, at whatever size that one declares, before any
    # header check can run. A story image is never an ICO, so the format is what gets refused.
    buffer = io.BytesIO()
    Image.new("RGB", (8, 8), "white").save(buffer, format="ICO", sizes=[(8, 8)])
    refusal(monkeypatch, base64.b64encode(buffer.getvalue()).decode())
