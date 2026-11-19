"""Offline evaluation: Precision@K, Recall@K and catalog coverage vs a popularity baseline.

Method: for every pseudonymous user with >= MIN_POSITIVES positive books, hold out 25% (at least
one) with a fixed seed, treat the rest as likes, and rank the full catalog (excluding training
items). Popularity used by both the model and the baseline is recomputed from training
interactions only, so held-out items do not leak. When there is not enough data the result says
so explicitly; numbers are never fabricated.
"""
from __future__ import annotations

from typing import Any

import numpy as np
import pandas as pd

from app.schemas import ForYouRequest, InteractionSet
from app.recommenders.scoring import for_you
from app.training.builder import ModelArtifacts

K = 5
MIN_USERS = 5
MIN_POSITIVES = 4
HOLDOUT_FRACTION = 0.25
SEED = 42


def evaluate(model: ModelArtifacts, interactions: list[InteractionSet], k: int = K, min_users: int = MIN_USERS,
             min_positives: int = MIN_POSITIVES, seed: int = SEED) -> dict[str, Any]:
    known = set(model.index)
    users = [sorted(set(u.positives) & known) for u in interactions]
    eligible = [p for p in users if len(p) >= min_positives]
    base = {
        "k": k,
        "usersWithInteractions": sum(1 for p in users if p),
        "eligibleUsers": len(eligible),
        "requiredUsers": min_users,
        "minPositivesPerUser": min_positives,
    }
    if len(eligible) < min_users:
        return {"status": "insufficient_data", **base,
                "message": f"Need at least {min_users} users with >= {min_positives} positive interactions; "
                           f"found {len(eligible)}. Metrics not computed."}

    rng = np.random.default_rng(seed)
    splits = []
    for positives in eligible:
        perm = rng.permutation(positives)
        n_test = max(1, int(round(HOLDOUT_FRACTION * len(perm))))
        splits.append((sorted(int(x) for x in perm[n_test:]), set(int(x) for x in perm[:n_test])))

    # Popularity from training interactions only (pandas for the count aggregation).
    train_rows = [b for train, _ in splits for b in train]
    counts = pd.Series(train_rows, dtype="int64").value_counts() if train_rows else pd.Series(dtype="int64")
    train_counts = np.array([counts.get(int(b), 0) for b in model.book_ids], dtype=float)
    pop_train = np.log1p(train_counts)
    pop_train = pop_train / pop_train.max() if pop_train.max() > 0 else pop_train

    model_hits, base_hits = [], []
    model_recall, base_recall = [], []
    model_seen: set[int] = set()
    base_seen: set[int] = set()
    pop_order = np.lexsort((model.book_ids, -train_counts))

    for train, test in splits:
        items, _ = for_you(model, ForYouRequest(liked_book_ids=train, limit=k), popularity=pop_train)
        recs = [r.book_id for r in items]
        model_seen.update(recs)
        hits = len(set(recs) & test)
        model_hits.append(hits / k)
        model_recall.append(hits / len(test))

        train_set = set(train)
        baseline = [int(model.book_ids[i]) for i in pop_order if int(model.book_ids[i]) not in train_set][:k]
        base_seen.update(baseline)
        bh = len(set(baseline) & test)
        base_hits.append(bh / k)
        base_recall.append(bh / len(test))

    n_books = model.book_count
    return {
        "status": "ok",
        **base,
        "usersEvaluated": len(splits),
        "method": f"per-user {int(HOLDOUT_FRACTION * 100)}% holdout, seed {seed}, popularity from training split only",
        "model": {
            f"precisionAt{k}": round(float(np.mean(model_hits)), 4),
            f"recallAt{k}": round(float(np.mean(model_recall)), 4),
            "catalogCoverage": round(len(model_seen) / n_books, 4),
        },
        "popularityBaseline": {
            f"precisionAt{k}": round(float(np.mean(base_hits)), 4),
            f"recallAt{k}": round(float(np.mean(base_recall)), 4),
            "catalogCoverage": round(len(base_seen) / n_books, 4),
        },
    }
