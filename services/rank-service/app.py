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

_model: lgb.Booster | None = None
_model_version = "none"


def _load_model() -> None:
    global _model, _model_version
    if not MODEL_PATH.exists():
        logger.warning("No model at %s — /rank will return no scores until train.py runs", MODEL_PATH)
        _model, _model_version = None, "none"
        return
    _model = lgb.Booster(model_file=str(MODEL_PATH))
    # mtime rather than a hash: the caller only logs this, and it has to change when the file does.
    _model_version = f"{MODEL_PATH.name}@{int(MODEL_PATH.stat().st_mtime)}"
    logger.info("Loaded %s with %d trees", _model_version, _model.num_trees())

    trained_on = list(_model.feature_name())
    if trained_on != FEATURE_NAMES:
        # Refuse rather than serve: a column order mismatch produces plausible scores from the
        # wrong inputs, which is the one failure nothing downstream can detect.
        raise RuntimeError(f"Model was trained on {trained_on}, this build serves {FEATURE_NAMES}")


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
    return {"status": "ok", "model_version": _model_version, "features": FEATURE_NAMES}


@app.post("/rank", response_model=RankResponse)
def rank(request: RankRequest) -> RankResponse:
    if _model is None or not request.candidates:
        return RankResponse(scores={}, model_version=_model_version)

    rows = [to_row(candidate.model_dump()) for candidate in request.candidates]
    predictions = _model.predict(rows)
    scores = {
        candidate.video_id: float(prediction)
        for candidate, prediction in zip(request.candidates, predictions)
    }
    return RankResponse(scores=scores, model_version=_model_version)


@app.post("/reload")
def reload_model() -> dict:
    """Picks up a newly trained model without a restart. Internal, same as everything else."""
    _load_model()
    return {"model_version": _model_version}
