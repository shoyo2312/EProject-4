"""Trains the feed ranking model from the watch log in ClickHouse.

Run it with the infra up:

    python train.py                     # writes model/model.txt and prints the evaluation
    python train.py --dry-run           # builds the dataset and evaluates, writes nothing

What it learns, precisely: given that someone started a video, did they watch it to the end.
That is not the same question as "would they have started it" — the watch log only contains
videos people actually watched, so every row is already the survivor of a decision this model
never sees. Fixing that needs impression logging (which candidates were served and ignored),
which nothing produces yet. Until then the model reorders the candidate set by predicted
completion, and candidate generation stays rule-based in FeedServiceImpl.

Features are reconstructed as of a cutoff instant and labels are taken strictly after it, so no
row can be scored using a watch that had not happened yet. Getting this backwards is the classic
way to train a model with a beautiful offline score and no effect in production.
"""

from __future__ import annotations

import argparse
import logging
import os
from datetime import datetime, timedelta
from pathlib import Path

import clickhouse_connect
import lightgbm as lgb
import numpy as np
import pandas as pd
from sklearn.metrics import roc_auc_score

from features import FEATURE_NAMES, TOP_TAGS, affinity_delta, completion_rate, log_watches

logging.basicConfig(level=logging.INFO, format="%(message)s")
logger = logging.getLogger("train")

MODEL_PATH = Path(os.getenv("MODEL_PATH", "model/model.txt"))

# How far after a cutoff a watch counts as that cutoff's label. A day is short enough that the
# features are still roughly true when the label lands, and long enough to collect any labels
# at all on a small instance.
LABEL_HORIZON = timedelta(days=1)


def connect():
    return clickhouse_connect.get_client(
        host=os.getenv("CLICKHOUSE_HOST", "localhost"),
        port=int(os.getenv("CLICKHOUSE_PORT", "8123")),
        username=os.getenv("CLICKHOUSE_USER", "default"),
        password=os.getenv("CLICKHOUSE_PASSWORD", "clickhouse"),
        database=os.getenv("CLICKHOUSE_DB", "analytics_db"),
    )


def _query(client, sql: str, parameters: dict) -> pd.DataFrame:
    return client.query_df(sql, parameters=parameters)


def user_tag_affinity(watches: pd.DataFrame, tags: pd.DataFrame) -> pd.DataFrame:
    """The offline twin of RecommendationServiceImpl.learnTags.

    The rule itself lives in features.affinity_delta and is applied here rather than expressed
    in SQL, so that the online profile and the training column cannot drift apart by someone
    editing one of two copies.
    """
    if watches.empty or tags.empty:
        return pd.DataFrame(columns=["user_id", "tag", "affinity"])

    watches = watches.copy()
    watches["delta"] = [
        affinity_delta(watched, duration)
        for watched, duration in zip(watches["watched_ms"], watches["duration_ms"])
    ]
    joined = watches.merge(tags[["video_id", "tag"]], on="video_id", how="inner")
    summed = joined.groupby(["user_id", "tag"], as_index=False)["delta"].sum().rename(
        columns={"delta": "affinity"})

    # Cut to the tags the feed would actually steer by, which is what FeedServiceImpl.topTags
    # reads: ZREVRANGE 0..TOP_TAGS-1 over the profile, non-positive scores discarded. A viewer
    # with forty tags contributes five of them to tag_affinity at serving time; summing all
    # forty here would put a systematically larger number in the same column, and the model
    # would fit a feature it is never shown.
    positive = summed[summed["affinity"] > 0]
    return (positive
            .sort_values("affinity", ascending=False)
            .groupby("user_id", as_index=False)
            .head(TOP_TAGS))


def build_dataset(client, cutoff: datetime) -> pd.DataFrame:
    """One training frame: features as of `cutoff`, labels from the window after it."""
    label_end = cutoff + LABEL_HORIZON

    labels = _query(
        client,
        """
        SELECT user_id, video_id, max(completed) AS label
        FROM watch_events FINAL
        WHERE occurred_at >= %(start)s AND occurred_at < %(end)s
        GROUP BY user_id, video_id
        """,
        {"start": cutoff, "end": label_end},
    )
    if labels.empty:
        return labels

    video_stats = _query(
        client,
        """
        SELECT video_id, count() AS watches, countIf(completed = 1) AS completions
        FROM watch_events FINAL
        WHERE occurred_at < %(cutoff)s
        GROUP BY video_id
        """,
        {"cutoff": cutoff},
    )

    past_watches = _query(
        client,
        """
        SELECT user_id, video_id, watched_ms, duration_ms
        FROM watch_events FINAL
        WHERE occurred_at < %(cutoff)s
        """,
        {"cutoff": cutoff},
    )

    tags = _query(client, "SELECT video_id, tag, published_at FROM video_tags FINAL", {})

    affinity = user_tag_affinity(past_watches, tags)

    data = labels.merge(video_stats, on="video_id", how="left")
    data["watches"] = data["watches"].fillna(0.0)
    data["completions"] = data["completions"].fillna(0.0)

    data["log_watches"] = data["watches"].map(log_watches)
    data["completion_rate"] = [
        completion_rate(watches, completions)
        for watches, completions in zip(data["watches"], data["completions"])
    ]

    published = tags.groupby("video_id", as_index=False)["published_at"].min()
    data = data.merge(published, on="video_id", how="left")
    age = (cutoff - data["published_at"]).dt.total_seconds() / 3600.0
    # An untagged video has no row in video_tags and therefore no publish time here. It gets the
    # same stand-in FeedServiceImpl uses when the publish time has aged out of Redis.
    data["age_hours"] = age.fillna(24.0).clip(lower=0.0)

    if affinity.empty:
        data["tag_affinity"] = 0.0
        data["tag_overlap"] = 0
    else:
        # Restricted to the users and videos that actually appear in this dataset before the
        # join. Without it this is a cross product of every viewer's tags against every tagged
        # video, which on real data is orders of magnitude bigger than the rows it feeds.
        affinity = affinity[affinity["user_id"].isin(data["user_id"].unique())]
        tags = tags[tags["video_id"].isin(data["video_id"].unique())]

        # One tag set feeds both columns, because serving derives both from the same five tags:
        # affinityByVideo and tagOverlap in FeedServiceImpl are filled in the same loop over
        # topTags. Filtering one of them differently here is what made them disagree.
        per_pair = (
            tags[["video_id", "tag"]]
            .merge(affinity, on="tag", how="inner")
            .groupby(["user_id", "video_id"], as_index=False)["affinity"]
            .sum()
        )
        overlap = (
            tags[["video_id", "tag"]]
            .merge(affinity, on="tag", how="inner")
            .groupby(["user_id", "video_id"], as_index=False)["tag"]
            .count()
            .rename(columns={"tag": "tag_overlap"})
        )
        data = data.merge(per_pair, on=["user_id", "video_id"], how="left")
        data = data.merge(overlap, on=["user_id", "video_id"], how="left")
        data["tag_affinity"] = data["affinity"].fillna(0.0)
        data["tag_overlap"] = data["tag_overlap"].fillna(0).astype(int)

    return data[["user_id", "video_id", "label"] + FEATURE_NAMES]


def ndcg_at_k(data: pd.DataFrame, scores: np.ndarray, k: int = 10) -> float:
    """Averaged over users who have at least one positive — the rest have no ranking to get right."""
    frame = data[["user_id", "label"]].copy()
    frame["score"] = scores

    totals = []
    for _, group in frame.groupby("user_id"):
        if group["label"].sum() == 0:
            continue
        ranked = group.sort_values("score", ascending=False)["label"].to_numpy()[:k]
        ideal = np.sort(group["label"].to_numpy())[::-1][:k]
        discounts = 1.0 / np.log2(np.arange(2, len(ranked) + 2))
        dcg = float((ranked * discounts).sum())
        idcg = float((ideal * discounts[: len(ideal)]).sum())
        if idcg > 0:
            totals.append(dcg / idcg)
    return float(np.mean(totals)) if totals else float("nan")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dry-run", action="store_true", help="evaluate without writing a model")
    args = parser.parse_args()

    client = connect()
    latest = client.query("SELECT max(occurred_at) FROM watch_events").result_rows[0][0]
    if latest is None:
        raise SystemExit("watch_events is empty — nothing to train on. Is analytics-service consuming?")

    # Two cutoffs, the earlier one for training: evaluating on a later window is the only split
    # that answers the question actually being asked, which is whether the model works tomorrow.
    eval_cutoff = latest - LABEL_HORIZON
    train_cutoff = eval_cutoff - LABEL_HORIZON

    train = build_dataset(client, train_cutoff)
    evaluation = build_dataset(client, eval_cutoff)
    logger.info("train rows: %d (cutoff %s)", len(train), train_cutoff)
    logger.info("eval rows:  %d (cutoff %s)", len(evaluation), eval_cutoff)

    if train.empty or evaluation.empty:
        raise SystemExit(
            "Not enough history for a time split. Needs watch events spanning at least "
            f"{2 * LABEL_HORIZON.days} days."
        )
    if train["label"].nunique() < 2:
        raise SystemExit("Training labels are all one class — no signal to learn.")

    model = lgb.train(
        {
            "objective": "binary",
            "metric": "auc",
            "learning_rate": 0.05,
            # Small on purpose. A few thousand watch rows will overfit anything larger, and the
            # honest fix for that is more data, not more capacity.
            "num_leaves": 15,
            "min_data_in_leaf": 20,
            "verbosity": -1,
        },
        lgb.Dataset(train[FEATURE_NAMES], label=train["label"]),
        num_boost_round=200,
    )

    predictions = model.predict(evaluation[FEATURE_NAMES])
    auc = (
        roc_auc_score(evaluation["label"], predictions)
        if evaluation["label"].nunique() > 1
        else float("nan")
    )
    logger.info("AUC     %.4f  (0.5 is a coin flip — below that the model is worse than nothing)", auc)
    logger.info("nDCG@10 %.4f", ndcg_at_k(evaluation, predictions))
    logger.info("importance: %s", dict(zip(FEATURE_NAMES, model.feature_importance().tolist())))

    if args.dry_run:
        logger.info("dry run — model not written")
        return

    MODEL_PATH.parent.mkdir(parents=True, exist_ok=True)
    model.save_model(str(MODEL_PATH))
    logger.info("wrote %s — POST /reload on rank-service to pick it up", MODEL_PATH)


if __name__ == "__main__":
    main()
