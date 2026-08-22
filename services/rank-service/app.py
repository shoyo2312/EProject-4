"""Scores feed candidates with a trained LightGBM model.

Internal network only. It holds no user data, stores nothing, and does no authentication —
recommendation-service is the only caller and reaches it over the compose network. Do not put
this behind the public gateway.

When no model file is present the service still starts and still answers, saying so in
`model_version`. That is deliberate: a fresh checkout has no model until someone runs train.py,
and the caller already falls back to its own ordering when it gets nothing usable back.
"""

from __future__ import annotations

import logging
import os
from contextlib import asynccontextmanager
from pathlib import Path

import lightgbm as lgb
from fastapi import FastAPI
from pydantic import BaseModel

from features import FEATURE_NAMES, to_row

logger = logging.getLogger("rank-service")

MODEL_PATH = Path(os.getenv("MODEL_PATH", "model/model.txt"))

# Model and its version as one tuple, rebound in a single statement. /reload runs while /rank is
# being served, and two globals cannot be swapped together: a request landing between the two
# assignments would score with one model and report the other version, and — worse — the old code
# published the booster before checking its feature names, so a model it was about to refuse was
# already answering requests. Rebinding one name is atomic under the GIL, so readers see either
# the whole old pair or the whole new one and no lock is needed.
_state: tuple[lgb.Booster | None, str] = (None, "none")


def _load_model() -> None:
    global _state
    if not MODEL_PATH.exists():
        logger.warning("No model at %s — /rank will return no scores until train.py runs", MODEL_PATH)
        _state = (None, "none")
        return

    model = lgb.Booster(model_file=str(MODEL_PATH))

    trained_on = list(model.feature_name())
    if trained_on != FEATURE_NAMES:
        # Refuse rather than serve: a column order mismatch produces plausible scores from the
        # wrong inputs, which is the one failure nothing downstream can detect. Checked before
        # publishing, so a refused model never answers a single request.
        raise RuntimeError(f"Model was trained on {trained_on}, this build serves {FEATURE_NAMES}")

    # mtime rather than a hash: the caller only logs this, and it has to change when the file does.
    version = f"{MODEL_PATH.name}@{int(MODEL_PATH.stat().st_mtime)}"
    logger.info("Loaded %s with %d trees", version, model.num_trees())
    _state = (model, version)


@asynccontextmanager
async def lifespan(_: FastAPI):
    _load_model()
    yield


app = FastAPI(title="rank-service", docs_url="/docs", lifespan=lifespan)


class Candidate(BaseModel):
    video_id: str
    log_watches: float
    completion_rate: float
    age_hours: float
    tag_affinity: float
    tag_overlap: int


class RankRequest(BaseModel):
    user_id: int
    candidates: list[Candidate]


class RankResponse(BaseModel):
    scores: dict[str, float]
    model_version: str


@app.get("/health")
def health() -> dict:
    return {"status": "ok", "model_version": _state[1], "features": FEATURE_NAMES}


@app.post("/rank", response_model=RankResponse)
def rank(request: RankRequest) -> RankResponse:
    # One read of the pair, so a /reload landing mid-request cannot change the model out from
    # under the scores that are already being computed.
    model, version = _state
    if model is None or not request.candidates:
        return RankResponse(scores={}, model_version=version)

    rows = [to_row(candidate.model_dump()) for candidate in request.candidates]
    predictions = model.predict(rows)
    scores = {
        candidate.video_id: float(prediction)
        for candidate, prediction in zip(request.candidates, predictions)
    }
    return RankResponse(scores=scores, model_version=version)


@app.post("/reload")
def reload_model() -> dict:
    """Picks up a newly trained model without a restart. Internal, same as everything else."""
    _load_model()
    return {"model_version": _state[1]}
