"""Automatic content moderation for uploaded videos, and for the text around them.

media-worker samples frames from a transcoded video and posts them here; this service scores
them and answers with a verdict. interaction-service and video-service post comments and captions
to /moderate/text, after their own word rules have run. It holds no state, no user data and no
auth — it is reachable only on the internal Docker network, like rank-service, and is not routed
at the gateway.

Frames are scored by two models. An off-the-shelf ViT classifier scores NSFW imagery, and is the
only one allowed to reject. CLIP, zero-shot, scores violence, gore, drugs, accidents and gambling
(harm_classifier.py), and can only send a video to a person. The severity ordering that combines
them lives in verdict.py.

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
from text_verdict import TextThresholds, decide_text
from verdict import REVIEW, Decision, Thresholds, decide, review_only, worst

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

# The text classifier. Empty switches /moderate/text off (503) and leaves frames alone: the callers
# fail open to their word rules, so a deployment without the text model is a Phase 1 deployment.
TEXT_MODEL = os.getenv("TEXT_MODERATION_MODEL", "visolex/phobert-v2-hsd")
# A commit, not a branch: the image is built from bytes somebody looked at, and a push to the
# repository's main must not change what this service runs on its next rebuild.
TEXT_MODEL_REVISION = os.getenv("TEXT_MODERATION_MODEL_REVISION", "221aca47de6568d519eba61a94d7fdae3ca680ae")
# Bumped by hand when the weights or the threshold change, like MODERATION_MODEL_VERSION. It ends up
# in the version string stored with every comment and video the classifier flagged.
TEXT_MODEL_VERSION = os.getenv("TEXT_MODERATION_MODEL_VERSION", "hsd-v1")

TEXT_THRESHOLDS = TextThresholds(
    hate_hold_at=float(os.getenv("TEXT_HATE_HOLD_THRESHOLD", "0.90")),
)

# The zero-shot scorer for violence, gore, drugs, accidents and gambling. Empty switches it off and
# leaves the NSFW check exactly as it was. Baked in at build, like the other two.
HARM_MODEL = os.getenv("HARM_MODERATION_MODEL", "openai/clip-vit-base-patch16")
HARM_MODEL_REVISION = os.getenv("HARM_MODERATION_MODEL_REVISION", "57c216476eefef5ab752ec549e440a49ae4ae5f3")
# Bumped by hand when the weights, the threshold or any description in harm_classifier changes:
# the descriptions are the model, see that module.
HARM_MODEL_VERSION = os.getenv("HARM_MODERATION_MODEL_VERSION", "harm-v1")

# One frame at or over this sends the video to a person; nothing here rejects (review_only). 0.55
# sat between the highest ordinary video (0.44) and the lowest harmful one caught (0.64) on the
# first judged set — see docs/moderation.md before moving it.
HARM_THRESHOLDS = review_only(float(os.getenv("HARM_REVIEW_THRESHOLD", "0.55")))

# The labels Azure has an opinion on. A REVIEW caused by anything else must not be escalated:
# Azure has no drugs or gambling category, answers severity 0, and that would approve the very
# video the local model held back.
ESCALATABLE = frozenset({"nsfw"})

# Caps, for the same reason as MAX_FRAMES: a CPU shared by every caller. A request over the text
# cap is refused rather than trimmed — unlike frames, every text needs its own answer, and the
# callers send one to three.
TEXT_MAX_TEXTS = int(os.getenv("TEXT_MAX_TEXTS", "16"))
# 32 pieces of 64 tokens, 48 apart, is about 1,500 tokens: a 2,000-character description, whole.
TEXT_MAX_WINDOWS = int(os.getenv("TEXT_MAX_WINDOWS", "32"))

_classifier = None
_text_scorer = None
_harm_scorer = None


@asynccontextmanager
async def lifespan(_: FastAPI):
    global _classifier, _text_scorer, _harm_scorer
    from transformers import pipeline

    log.info("Loading %s", MODEL)
    started = time.monotonic()
    # device=-1 pins it to CPU. There is no GPU in this deployment and letting transformers pick
    # would only ever surprise us on a machine that has one.
    _classifier = pipeline("image-classification", model=MODEL, device=-1)
    log.info("Loaded %s in %.1fs", MODEL, time.monotonic() - started)

    if TEXT_MODEL:
        import text_classifier

        started = time.monotonic()
        _text_scorer = text_classifier.TextScorer(
            text_classifier.local_path(TEXT_MODEL, TEXT_MODEL_REVISION), TEXT_MAX_WINDOWS)
        log.info("Loaded %s@%s in %.1fs", TEXT_MODEL, TEXT_MODEL_REVISION[:7], time.monotonic() - started)

    if HARM_MODEL:
        import harm_classifier

        started = time.monotonic()
        _harm_scorer = harm_classifier.HarmScorer(harm_classifier.local_path(HARM_MODEL, HARM_MODEL_REVISION))
        log.info("Loaded %s@%s in %.1fs", HARM_MODEL, HARM_MODEL_REVISION[:7], time.monotonic() - started)
    yield
    _classifier = None
    _text_scorer = None
    _harm_scorer = None


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


# An animated image (GIF, APNG, animated WebP — a story image is the uploaded file itself) is scored
# like a short video: frames spread from first to last. Read as one image it was its first frame,
# so a clean frame 0 in front of anything at all was all that got looked at.
# ponytail: spread by index, so a flash shorter than the gap between samples can slip, as it can
# between a video's sampled frames; weigh by frame duration if that turns up.
ANIMATION_FRAMES = 16
# A still image's one frame is used as it is; sampled frames are kept, so each is shrunk on the spot
# — sixteen full-size frames of a large canvas is gigabytes. Both models look at 224 pixels.
ANIMATION_FRAME_SIDE = 512


def _decode(frames: list[str]) -> tuple[list[Image.Image], list[str]]:
    """The images to score and, beside each, the base64 JPEG escalation would send for it.

    The two lists stay aligned because escalation picks its frames by local score, index by index.
    """
    images, sources = [], []
    for index, encoded in enumerate(frames):
        try:
            raw = base64.b64decode(encoded, validate=True)
            image = Image.open(io.BytesIO(raw))
            count = getattr(image, "n_frames", 1)
            if count <= 1:
                image.load()
                images.append(image.convert("RGB"))
                sources.append(encoded)
                continue
            for position in _spread(count, ANIMATION_FRAMES):
                image.seek(position)
                frame = image.convert("RGB")
                frame.thumbnail((ANIMATION_FRAME_SIDE, ANIMATION_FRAME_SIDE))
                images.append(frame)
                sources.append(_jpeg(frame))
        except (binascii.Error, ValueError, UnidentifiedImageError, OSError, EOFError) as error:
            raise HTTPException(status_code=400, detail=f"frame {index} is not a decodable image: {error}")
    return images, sources


def _spread(count: int, wanted: int) -> list[int]:
    """Up to ``wanted`` frame positions, evenly from the first to the last."""
    if count <= wanted:
        return list(range(count))
    return [round(i * (count - 1) / (wanted - 1)) for i in range(wanted)]


def _jpeg(image: Image.Image) -> str:
    buffer = io.BytesIO()
    image.save(buffer, format="JPEG", quality=90)
    return base64.b64encode(buffer.getvalue()).decode("ascii")


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
    images, frames = _decode(frames)
    # Again after decoding: every animated image is up to ANIMATION_FRAMES of them.
    images, frames = images[:MAX_FRAMES], frames[:MAX_FRAMES]
    scored = {label: scorer(images) for label, scorer in SCORERS.items()}
    decisions: dict[str, Decision] = {
        label: decide(scores, THRESHOLDS) for label, scores in scored.items()
    }
    if _harm_scorer is not None:
        harm = _harm_scorer(images)
        scored |= harm
        decisions |= {label: decide(scores, HARM_THRESHOLDS) for label, scores in harm.items()}
    label, decision = worst(decisions)

    verdict = decision.verdict
    # Both versions whenever both models ran: an APPROVED video was cleared by each of them, and
    # which one's label it carries says nothing about which setup let it through.
    model_version = f"{MODEL_VERSION}+{HARM_MODEL_VERSION}" if _harm_scorer is not None else MODEL_VERSION

    # Only the band the local model is bad at, and only when a second opinion is configured.
    # An APPROVED or REJECTED video is already decided; paying quota to re-ask would spend the
    # month's allowance on the cases that never needed it. And only when every label asking for a
    # person is one Azure can answer — an approval from Azure must not clear a video some other
    # label held back.
    reviewing = {held for held, held_decision in decisions.items() if held_decision.verdict == REVIEW}
    if verdict == REVIEW and reviewing <= ESCALATABLE and escalation.enabled():
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
        model_version = f"{model_version}+{opinion.code}"

    elapsed_ms = int((time.monotonic() - started) * 1000)

    # Every label's worst frame, not only the winner's: the response carries one label, and the
    # harm threshold is tuned against how close ordinary videos come on the labels that lost.
    log.info("videoFrames=%d verdict=%s label=%s max=%.3f suspicious=%d scores=%s in %dms",
             len(images), verdict, label, decision.max_score, decision.suspicious_frames,
             ",".join(f"{name}:{max(values, default=0.0):.3f}" for name, values in scored.items()), elapsed_ms)

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
        # The model that produced maxScore, so a score is never read against the wrong scale.
        model=MODEL if label in SCORERS else HARM_MODEL,
        modelVersion=model_version,
    )


class ModerateTextRequest(BaseModel):
    """Texts already sanitized by the caller: a comment, or a video's title, description and tags."""

    texts: list[str] = Field(default_factory=list)


class TextResult(BaseModel):
    verdict: str
    label: str
    score: float
    scores: dict[str, float]


class ModerateTextResponse(BaseModel):
    results: list[TextResult]
    model: str
    modelVersion: str
    processingTimeMs: int


@app.post("/moderate/text", response_model=ModerateTextResponse)
def moderate_text(request: ModerateTextRequest) -> ModerateTextResponse:
    """One result per text, in the order sent. HOLD only on HATE — see text_verdict.TextThresholds."""
    if not TEXT_MODEL:
        raise HTTPException(status_code=503, detail="text moderation is switched off")
    if _text_scorer is None:
        raise HTTPException(status_code=503, detail="model is still loading")
    if len(request.texts) > TEXT_MAX_TEXTS:
        raise HTTPException(status_code=400, detail=f"at most {TEXT_MAX_TEXTS} texts per request")

    started = time.monotonic()
    # Blank texts are clean without asking the model; the rest go through in one forward pass.
    asked = [index for index, text in enumerate(request.texts) if text and text.strip()]
    scored = _text_scorer([request.texts[index] for index in asked]) if asked else []
    per_text: list[list[dict]] = [[] for _ in request.texts]
    for index, text_windows in zip(asked, scored):
        per_text[index] = text_windows

    decisions = [decide_text(text_windows, TEXT_THRESHOLDS) for text_windows in per_text]
    elapsed_ms = int((time.monotonic() - started) * 1000)

    # Counts and labels, never the text: a log line per comment is a second copy of every comment.
    log.info("texts=%d held=%d labels=%s in %dms", len(decisions),
             sum(1 for decision in decisions if decision.verdict == "HOLD"),
             ",".join(decision.label for decision in decisions), elapsed_ms)

    return ModerateTextResponse(
        results=[TextResult(verdict=decision.verdict, label=decision.label, score=round(decision.score, 4),
                            scores=decision.scores) for decision in decisions],
        model=TEXT_MODEL,
        modelVersion=TEXT_MODEL_VERSION,
        processingTimeMs=elapsed_ms,
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
        "textEnabled": bool(TEXT_MODEL),
        "textModel": TEXT_MODEL,
        "textModelRevision": TEXT_MODEL_REVISION,
        "textModelVersion": TEXT_MODEL_VERSION,
        "textHateHoldAt": TEXT_THRESHOLDS.hate_hold_at,
        "textMaxTexts": TEXT_MAX_TEXTS,
        "harmEnabled": bool(HARM_MODEL),
        "harmModel": HARM_MODEL,
        "harmModelRevision": HARM_MODEL_REVISION,
        "harmModelVersion": HARM_MODEL_VERSION,
        "harmReviewAt": HARM_THRESHOLDS.review_at,
        "harmLabels": list(_harm_labels()),
    }


def _harm_labels() -> tuple[str, ...]:
    """The labels the harm scorer reports, without importing torch when it is switched off."""
    if not HARM_MODEL:
        return ()
    import harm_classifier

    return tuple(harm_classifier.CATEGORIES)


@app.get("/health")
def health() -> dict:
    """Unhealthy until every configured model is in memory, so nothing is routed here mid-load."""
    if _classifier is None or (TEXT_MODEL and _text_scorer is None) or (HARM_MODEL and _harm_scorer is None):
        raise HTTPException(status_code=503, detail="model is still loading")
    return {"status": "up", "model": MODEL, "modelVersion": MODEL_VERSION,
            "textModel": TEXT_MODEL or None, "textModelVersion": TEXT_MODEL_VERSION if TEXT_MODEL else None,
            "harmModel": HARM_MODEL or None, "harmModelVersion": HARM_MODEL_VERSION if HARM_MODEL else None}
