"""Automatic content moderation for uploaded videos.

media-worker samples frames from a transcoded video and posts them here; this service scores
them and answers with a verdict. It holds no state, no user data and no auth — it is reachable
only on the internal Docker network, like rank-service, and is not routed at the gateway.

V1 scores one thing: NSFW imagery, with an off-the-shelf ViT classifier that runs on CPU. Adding
violence or weapon detection later is a second entry in SCORERS; the severity ordering that
combines them already lives in verdict.py.

The model is loaded once at startup, not per request: loading it is seconds and scoring a frame
is milliseconds, so a lazy load would put the cost on whichever upload happened to arrive first
after a restart. It is also baked into the image at build time and read with HF_HUB_OFFLINE=1, so
a container that starts has a model — it cannot come up healthy and then fail on the first video
because a download failed.
"""

import base64
import binascii
import io
import logging
import os
import time
from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException
from PIL import Image, UnidentifiedImageError
from pydantic import BaseModel, Field

import escalation
from verdict import Decision, Thresholds, decide, worst

log = logging.getLogger("moderation")
logging.basicConfig(level=logging.INFO)

MODEL = os.getenv("MODERATION_MODEL", "Falconsai/nsfw_image_detection")
# Bumped by hand when the weights or the thresholds change. It travels on every verdict so a
# decision made months ago can still be attributed to the setup that made it.
MODEL_VERSION = os.getenv("MODERATION_MODEL_VERSION", "nsfw-v1")

THRESHOLDS = Thresholds(
    review_at=float(os.getenv("MODERATION_REVIEW_THRESHOLD", "0.60")),
    reject_at=float(os.getenv("MODERATION_REJECT_THRESHOLD", "0.90")),
    min_reject_frames=int(os.getenv("MODERATION_MIN_REJECT_FRAMES", "2")),
)

# A cap rather than a promise: the caller decides how many frames to send, this stops a malformed
# or hostile request from turning into an unbounded batch on a CPU-only box. Frames past it are
# dropped rather than refused — media-worker's frame count is a separate environment variable in a
# separate container, and refusing meant raising that one past this one turned every upload on the
# platform into a 400, three retries and a trip through the admin queue, with nothing in either
# service's log naming the cause. "Use the first 32" is the honest answer to an internal caller
# that sent 40.
MAX_FRAMES = int(os.getenv("MODERATION_MAX_FRAMES", "32"))

# What the classifier calls the class we act on. Renaming it here is how you point this at a
# different NSFW model whose labels are spelled differently.
NSFW_LABEL = os.getenv("MODERATION_NSFW_LABEL", "nsfw")

_classifier = None


@asynccontextmanager
async def lifespan(_: FastAPI):
    global _classifier
    from transformers import pipeline

    log.info("Loading %s", MODEL)
    started = time.monotonic()
    # device=-1 pins it to CPU. There is no GPU in this deployment and letting transformers pick
    # would only ever surprise us on a machine that has one.
    _classifier = pipeline("image-classification", model=MODEL, device=-1)
    log.info("Loaded %s in %.1fs", MODEL, time.monotonic() - started)
    yield
    _classifier = None


app = FastAPI(title="moderation-service", lifespan=lifespan)


class ModerateRequest(BaseModel):
    """Frames as base64 JPEG, already sampled and downscaled by the caller."""

    frames: list[str] = Field(default_factory=list)


class ModerateResponse(BaseModel):
    """Field names are camelCase to land straight on the Java record that reads them."""

    verdict: str
    label: str | None
    maxScore: float
    suspiciousFrames: int
    totalFrames: int
    processingTimeMs: int
    model: str
    modelVersion: str


def score_nsfw(images: list[Image.Image]) -> list[float]:
    """Probability of the NSFW class for each image, in the order given.

    top_k=None asks for every class rather than only the winner: a frame the model calls "normal"
    at 0.55 still carries a 0.45 NSFW score, and that number is what the thresholds are about.
    Taking only the top label would collapse the whole borderline band into a clean pass.
    """
    results = _classifier(images, top_k=None)
    # For a single image transformers returns one flat list of scores rather than a list of them.
    if images and results and isinstance(results[0], dict):
        results = [results]

    scores = []
    for per_image in results:
        match = next((entry for entry in per_image if entry["label"].lower() == NSFW_LABEL), None)
        # A model whose labels do not include ours cannot clear a video; that is a
        # misconfiguration, and the fail-safe for a misconfiguration is a human.
        scores.append(float(match["score"]) if match else 1.0)
    return scores


# Add a detector by adding an entry. Nothing else changes: verdict.worst() already combines them.
SCORERS = {"nsfw": score_nsfw}


def _decode(frames: list[str]) -> list[Image.Image]:
    images = []
    for index, encoded in enumerate(frames):
        try:
            raw = base64.b64decode(encoded, validate=True)
            image = Image.open(io.BytesIO(raw))
            image.load()
            images.append(image.convert("RGB"))
        except (binascii.Error, ValueError, UnidentifiedImageError, OSError) as error:
            raise HTTPException(status_code=400, detail=f"frame {index} is not a decodable image: {error}")
    return images


@app.post("/moderate", response_model=ModerateResponse)
def moderate(request: ModerateRequest) -> ModerateResponse:
    if _classifier is None:
        # 503, not 500: the caller retries this, and a retry after the model finishes loading
        # succeeds. A 500 would be treated as permanent.
        raise HTTPException(status_code=503, detail="model is still loading")
    frames = request.frames
    if len(frames) > MAX_FRAMES:
        log.warning("Asked about %d frames, scoring the first %d", len(frames), MAX_FRAMES)
        frames = frames[:MAX_FRAMES]

    started = time.monotonic()
    images = _decode(frames)
    scored = {label: scorer(images) for label, scorer in SCORERS.items()}
    decisions: dict[str, Decision] = {
        label: decide(scores, THRESHOLDS) for label, scores in scored.items()
    }
    label, decision = worst(decisions)

    verdict = decision.verdict
    model_version = MODEL_VERSION

    # Only the band the local model is bad at, and only when a second opinion is configured.
    # An APPROVED or REJECTED video is already decided; paying quota to re-ask would spend the
    # month's allowance on the cases that never needed it.
    if verdict == "REVIEW" and escalation.enabled():
        opinion = escalation.second_opinion(
            escalation.top_frames(frames, scored[label])
        )
        # A verdict of None means nothing usable came back, and the video stays where it was:
        # with a human. Failure is never a pass.
        if opinion.verdict is not None:
            verdict = opinion.verdict
        # Recorded either way — a month of REVIEWs caused by an exhausted quota should be
        # readable off the videos themselves, not only off a log that has since rolled. A short
        # code rather than the failure's own words: this lands on the video document and on the
        # admin console, and a message carrying an endpoint URL and a socket error both leaks
        # where the service lives and makes the field ungroupable — which is the one thing it is
        # for. The words are in escalation's own log line.
        model_version = f"{MODEL_VERSION}+{opinion.code}"

    elapsed_ms = int((time.monotonic() - started) * 1000)

    log.info("videoFrames=%d verdict=%s label=%s max=%.3f suspicious=%d in %dms",
             len(images), verdict, label, decision.max_score, decision.suspicious_frames, elapsed_ms)

    return ModerateResponse(
        verdict=verdict,
        label=label,
        # The local model's numbers, kept as they were even when Azure overruled the verdict:
        # they are what the local thresholds get tuned against, and mixing in a 0-6 severity
        # would make that series meaningless.
        maxScore=round(decision.max_score, 4),
        suspiciousFrames=decision.suspicious_frames,
        totalFrames=len(images),
        processingTimeMs=elapsed_ms,
        model=MODEL,
        modelVersion=model_version,
    )


@app.get("/config")
def config() -> dict:
    """What this service is currently deciding with, for the admin console to show.

    Read-only on purpose. Every value here comes from an environment variable read once at
    startup, so there is nothing to write back to: changing a threshold is a deploy, and an
    endpoint that accepted one would either lie until the next restart or drift away from the
    variable that is still the source of truth. The console says as much next to the numbers.
    """
    return {
        "model": MODEL,
        "modelVersion": MODEL_VERSION,
        "nsfwLabel": NSFW_LABEL,
        "maxFrames": MAX_FRAMES,
        "reviewAt": THRESHOLDS.review_at,
        "rejectAt": THRESHOLDS.reject_at,
        "minRejectFrames": THRESHOLDS.min_reject_frames,
        "escalationEnabled": escalation.enabled(),
        "escalationFrames": escalation.ESCALATE_FRAMES,
        "escalationCategories": list(escalation.CATEGORIES),
        "escalationRejectSeverity": escalation.REJECT_SEVERITY,
        "escalationApproveSeverity": escalation.APPROVE_SEVERITY,
    }


@app.get("/health")
def health() -> dict:
    """Unhealthy until the model is in memory, so nothing is routed here mid-load."""
    if _classifier is None:
        raise HTTPException(status_code=503, detail="model is still loading")
    return {"status": "up", "model": MODEL, "modelVersion": MODEL_VERSION}
