"""A second opinion from Azure AI Content Safety, for the videos the local model is unsure about.

Only the REVIEW band is escalated. The local classifier scores an ordinary video around 0.01 and
explicit content around 0.99; both ends it settles on its own. The 0.60-0.90 band is the part it
is genuinely bad at, and it is also the part that costs a human their afternoon — which makes it
the only part worth spending a metered quota on.

Escalating everything instead would burn the free tier in a week and buy nothing: 5,000 images a
month is 500 videos at ten frames each, whereas escalating only the uncertain videos and only the
frames that made them uncertain stretches the same quota across far more uploads.

Never a pass on failure. A quota exhausted, a network blip and a malformed answer all resolve the
same way as before the call: REVIEW, and a person looks at it. This exists to catch what the local
model missed, not to become a new way for a video to slip through.
"""

import logging
import os
from dataclasses import dataclass

import httpx

log = logging.getLogger("moderation.escalation")

# Azure returns severity on a 0/2/4/6 scale under FourSeverityLevels. Two boundaries, so a
# confident answer either way ends the matter and anything in between still gets a human.
REJECT_SEVERITY = int(os.getenv("MODERATION_AZURE_REJECT_SEVERITY", "4"))
APPROVE_SEVERITY = int(os.getenv("MODERATION_AZURE_APPROVE_SEVERITY", "0"))

# How many frames to send. The ones that scored highest locally, not the first few: they are what
# put the video in the review band, so they are what a second opinion has to be about.
ESCALATE_FRAMES = int(os.getenv("MODERATION_ESCALATE_FRAMES", "3"))

ENDPOINT = os.getenv("AZURE_CONTENT_SAFETY_ENDPOINT", "").rstrip("/")
KEY = os.getenv("AZURE_CONTENT_SAFETY_KEY", "")
API_VERSION = os.getenv("AZURE_CONTENT_SAFETY_API_VERSION", "2024-09-01")
TIMEOUT_SECONDS = float(os.getenv("MODERATION_ESCALATE_TIMEOUT", "10"))

# Which categories to ask about. Sexual is the one the local model has an opinion on, so it is the
# one being second-guessed; the others come back in the same response at no extra quota, and a
# violent video that happens to pass an NSFW model should not be published just because nobody
# asked. Severity is taken as the worst across all of them.
CATEGORIES = tuple(
    category.strip()
    for category in os.getenv(
        "MODERATION_AZURE_CATEGORIES", "Sexual,Violence,SelfHarm,Hate"
    ).split(",")
    if category.strip()
)


@dataclass(frozen=True)
class SecondOpinion:
    """What the escalation concluded. `verdict` is None when nothing usable came back."""

    verdict: str | None
    detail: str


def enabled() -> bool:
    """No endpoint or no key means the feature is off, and the service behaves exactly as before."""
    return bool(ENDPOINT and KEY)


def resolve(severity: int) -> str:
    """Turn an Azure severity into a verdict.

    Kept separate from the HTTP call so the part that decides is testable without a network or an
    account — these boundaries are what will get tuned, and tuning something you cannot run is how
    thresholds end up never being revisited.
    """
    if severity >= REJECT_SEVERITY:
        return "REJECTED"
    if severity <= APPROVE_SEVERITY:
        return "APPROVED"
    return "REVIEW"


def worst_severity(payload: dict) -> int | None:
    """The highest severity across the categories analysed, or None if the shape is unfamiliar.

    An unfamiliar shape is not read optimistically: a response this cannot parse yields no verdict
    at all, which leaves the video with a human rather than inventing a zero.
    """
    analyses = payload.get("categoriesAnalysis")
    if not isinstance(analyses, list) or not analyses:
        return None
    severities = [
        entry.get("severity")
        for entry in analyses
        if isinstance(entry, dict) and isinstance(entry.get("severity"), int)
    ]
    return max(severities) if severities else None


def top_frames(frames: list[str], scores: list[float]) -> list[str]:
    """The frames that scored highest locally, worst first.

    Falls back to the given order when the two lists disagree in length, which should not happen
    but must not be the thing that stops a video from being checked.
    """
    if len(frames) != len(scores):
        return frames[:ESCALATE_FRAMES]
    ranked = sorted(zip(frames, scores), key=lambda pair: pair[1], reverse=True)
    return [frame for frame, _ in ranked[:ESCALATE_FRAMES]]


def second_opinion(frames: list[str]) -> SecondOpinion:
    """Ask Azure about the given base64 JPEG frames and combine the answers.

    One request per frame — the image endpoint takes a single image — and the worst severity wins,
    the same way the local decision takes the worst frame. Stops early on a confident rejection:
    there is nothing a later frame can add, and the quota is the point.
    """
    if not enabled():
        return SecondOpinion(None, "escalation is not configured")

    url = f"{ENDPOINT}/contentsafety/image:analyze?api-version={API_VERSION}"
    headers = {"Ocp-Apim-Subscription-Key": KEY, "Content-Type": "application/json"}
    worst = -1
    checked = 0

    try:
        with httpx.Client(timeout=TIMEOUT_SECONDS) as client:
            for frame in frames[:ESCALATE_FRAMES]:
                response = client.post(
                    url,
                    headers=headers,
                    json={
                        "image": {"content": frame},
                        "categories": list(CATEGORIES),
                        "outputType": "FourSeverityLevels",
                    },
                )
                # 429 is the free tier's monthly cap, and it is not a fault to fix — it is the
                # expected end of the quota. Same outcome as any other failure, but said plainly
                # so a month of REVIEWs is not mistaken for a broken integration.
                if response.status_code == 429:
                    log.warning("Azure content safety quota exhausted")
                    return SecondOpinion(None, "Azure quota exhausted for this period")
                response.raise_for_status()

                severity = worst_severity(response.json())
                if severity is None:
                    return SecondOpinion(None, "Azure returned an answer in an unfamiliar shape")
                checked += 1
                worst = max(worst, severity)
                if worst >= REJECT_SEVERITY:
                    break
    except httpx.HTTPError as error:
        log.warning("Azure content safety unreachable: %s", error)
        return SecondOpinion(None, f"Azure could not be reached: {error}")

    if worst < 0:
        return SecondOpinion(None, "no frames were sent for escalation")

    return SecondOpinion(resolve(worst), f"azure severity {worst} over {checked} frame(s)")
