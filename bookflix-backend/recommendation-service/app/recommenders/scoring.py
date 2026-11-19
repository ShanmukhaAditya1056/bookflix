"""Explainable hybrid scoring: TF-IDF content similarity + genre affinity + smoothed popularity.

for_you (established user, has positive signals):
    score = 0.60 * content_similarity + 0.30 * genre_match + 0.10 * popularity
for_you (cold start, only preferred genres):
    score = 0.75 * genre_match + 0.25 * popularity
for_you (no signals at all):
    score = popularity
similar:
    score = 0.85 * content_similarity + 0.10 * genre_jaccard + 0.05 * popularity

All scores are finite, clipped to [0, 1], rounded to 6 dp and ranked deterministically by
(score desc, book_id asc).
"""
from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass

import numpy as np

from app.schemas import ForYouRequest, Recommendation, SimilarRequest
from app.training.builder import ModelArtifacts

W_CONTENT, W_GENRE, W_POP = 0.60, 0.30, 0.10
COLD_GENRE, COLD_POP = 0.75, 0.25
SIM_CONTENT, SIM_GENRE, SIM_POP = 0.85, 0.10, 0.05
NEGATIVE_PENALTY = 0.5
CONTENT_REASON_MIN = 0.10  # raw cosine similarity above which "Similar to <book>" is a fair explanation

LIKE_WEIGHT = 1.0
RATING_WEIGHTS = {5: 1.0, 4: 0.6}
NEGATIVE_RATING_WEIGHTS = {1: 1.0, 2: 0.6}
# WANT_TO_READ is intent, not evidence of enjoyment: excluded from candidates but not a positive signal.
SHELF_WEIGHTS = {"COMPLETED": 0.8, "READING": 0.6, "WANT_TO_READ": 0.0}

CONTENT_HYBRID = "CONTENT_HYBRID"
GENRE_POPULARITY = "GENRE_POPULARITY"
POPULARITY = "POPULARITY"
CONTENT_SIMILARITY = "CONTENT_SIMILARITY"


class UnknownBookError(KeyError):
    pass


@dataclass
class UserProfile:
    positive: dict[int, float]          # book id -> weight (books present in the model)
    negative: dict[int, float]
    preferred_genres: list[int]
    interacted: set[int]


def build_profile(req: ForYouRequest, model: ModelArtifacts) -> UserProfile:
    pos: dict[int, float] = defaultdict(float)
    neg: dict[int, float] = defaultdict(float)
    interacted: set[int] = set()
    for b in req.liked_book_ids:
        pos[b] += LIKE_WEIGHT
        interacted.add(b)
    for r in req.ratings:
        interacted.add(r.book_id)
        if r.rating in RATING_WEIGHTS:
            pos[r.book_id] += RATING_WEIGHTS[r.rating]
        elif r.rating in NEGATIVE_RATING_WEIGHTS:
            neg[r.book_id] += NEGATIVE_RATING_WEIGHTS[r.rating]
    for s in req.shelf:
        interacted.add(s.book_id)
        pos[s.book_id] += SHELF_WEIGHTS[s.status]
    for b in neg:
        pos.pop(b, None)  # an explicit low rating overrides other positive signals
    return UserProfile(
        positive={b: w for b, w in pos.items() if b in model.index and w > 0},
        negative={b: w for b, w in neg.items() if b in model.index},
        preferred_genres=list(dict.fromkeys(req.preferred_genre_ids)),
        interacted=interacted,
    )


def _profile_similarity(model: ModelArtifacts, weights: dict[int, float]) -> np.ndarray:
    if not weights:
        return np.zeros(model.book_count)
    idx = np.fromiter((model.index[b] for b in weights), dtype=np.int64)
    w = np.fromiter(weights.values(), dtype=float)
    profile = model.matrix[idx].T @ w            # (vocab,)
    norm = np.linalg.norm(profile)
    if norm == 0:
        return np.zeros(model.book_count)
    sims = model.matrix @ (profile / norm)
    return np.clip(np.asarray(sims).ravel(), 0.0, 1.0)


def _genre_affinity(model: ModelArtifacts, profile: UserProfile) -> np.ndarray:
    """Per-genre affinity in [0, 1], aligned with model.genre_index columns."""
    aff = np.zeros(model.genre_matrix.shape[1])
    for g in profile.preferred_genres:
        if g in model.genre_index:
            aff[model.genre_index[g]] += 1.0
    for b, w in profile.positive.items():
        for g in model.book_genres[model.index[b]]:
            aff[model.genre_index[g]] += w
    for b, w in profile.negative.items():
        for g in model.book_genres[model.index[b]]:
            aff[model.genre_index[g]] -= NEGATIVE_PENALTY * w
    top = aff.max() if aff.size else 0.0
    return np.clip(aff / top, 0.0, 1.0) if top > 0 else np.zeros_like(aff)


def _genre_match(model: ModelArtifacts, affinity: np.ndarray) -> np.ndarray:
    if not affinity.any():
        return np.zeros(model.book_count)
    weighted = model.genre_matrix.multiply(affinity.reshape(1, -1)).tocsr()
    return np.asarray(weighted.max(axis=1).todense()).ravel()


def _finalise(scores: np.ndarray) -> np.ndarray:
    return np.round(np.clip(np.nan_to_num(scores, nan=0.0, posinf=1.0, neginf=0.0), 0.0, 1.0), 6)


def _rank(model: ModelArtifacts, scores: np.ndarray, exclude: set[int], offset: int, limit: int) -> list[int]:
    order = np.lexsort((model.book_ids, -scores))  # score desc, then book id asc
    picked: list[int] = []
    skipped = 0
    for i in order:
        if int(model.book_ids[i]) in exclude:
            continue
        if skipped < offset:
            skipped += 1
            continue
        picked.append(int(i))
        if len(picked) == limit:
            break
    return picked


def for_you(model: ModelArtifacts, req: ForYouRequest,
            popularity: np.ndarray | None = None) -> tuple[list[Recommendation], str]:
    """Rank candidates for one user. ``popularity`` overrides the model's vector (used by offline eval)."""
    profile = build_profile(req, model)
    pop = model.popularity if popularity is None else popularity
    affinity = _genre_affinity(model, profile)
    genre = _genre_match(model, affinity)

    if profile.positive:
        content = np.clip(_profile_similarity(model, profile.positive)
                          - NEGATIVE_PENALTY * _profile_similarity(model, profile.negative), 0.0, 1.0)
        parts = (W_CONTENT * content, W_GENRE * genre, W_POP * pop)
        strategy = CONTENT_HYBRID
    elif affinity.any():
        content = np.zeros(model.book_count)
        parts = (np.zeros(model.book_count), COLD_GENRE * genre, COLD_POP * pop)
        strategy = GENRE_POPULARITY
    else:
        content = np.zeros(model.book_count)
        parts = (np.zeros(model.book_count), np.zeros(model.book_count), pop.astype(float))
        strategy = POPULARITY

    scores = _finalise(parts[0] + parts[1] + parts[2])
    exclude = set(req.exclude_book_ids) | profile.interacted
    picked = _rank(model, scores, exclude, req.offset, req.limit)

    items = []
    for i in picked:
        reason = _for_you_reason(model, i, parts, profile, affinity)
        items.append(Recommendation(book_id=int(model.book_ids[i]), score=float(scores[i]),
                                    reason=reason, strategy=strategy))
    return items, strategy


def _for_you_reason(model: ModelArtifacts, i: int, parts: tuple[np.ndarray, ...], profile: UserProfile,
                    affinity: np.ndarray) -> str:
    """Most specific explanation that is materially true: a similar liked book, then a genre, then popularity."""
    c, g, p = (float(part[i]) for part in parts)
    if profile.positive and (c / W_CONTENT >= CONTENT_REASON_MIN or (c > 0 and c >= g and c >= p)):
        anchor = _closest_positive(model, i, profile.positive)
        if anchor is not None:
            return f"Similar to {model.titles[model.index[anchor]]}"
    if g > 0 and g >= p:
        best = max(model.book_genres[i], key=lambda gid: (affinity[model.genre_index[gid]], -gid), default=None)
        if best is not None and affinity[model.genre_index[best]] > 0:
            return f"Matches your interest in {model.genre_names[best]}"
    return "Popular with Bookflix readers"


def _closest_positive(model: ModelArtifacts, i: int, positive: dict[int, float]) -> int | None:
    best, best_val = None, 0.0
    row = model.matrix[i]
    for b in sorted(positive):
        val = float(row.multiply(model.matrix[model.index[b]]).sum()) * positive[b]
        if val > best_val:
            best, best_val = b, val
    return best


def similar(model: ModelArtifacts, req: SimilarRequest) -> tuple[list[Recommendation], str]:
    if req.book_id not in model.index:
        raise UnknownBookError(req.book_id)
    src = model.index[req.book_id]
    content = np.clip(np.asarray(model.matrix @ model.matrix[src].T.toarray()).ravel(), 0.0, 1.0)

    src_genres = set(model.book_genres[src])
    jaccard = np.array([
        len(src_genres & set(g)) / len(src_genres | set(g)) if (src_genres or g) else 0.0
        for g in model.book_genres
    ])
    parts = (SIM_CONTENT * content, SIM_GENRE * jaccard, SIM_POP * model.popularity)
    scores = _finalise(parts[0] + parts[1] + parts[2])
    exclude = set(req.exclude_book_ids) | {req.book_id}
    picked = _rank(model, scores, exclude, req.offset, req.limit)

    source_title = model.titles[src]
    items = []
    for i in picked:
        shared = sorted(src_genres & set(model.book_genres[i]))
        if parts[0][i] >= parts[1][i] and content[i] > 0:
            reason = f"Similar themes to {source_title}"
        elif shared:
            reason = f"Also in {model.genre_names[shared[0]]}"
        else:
            reason = "Popular with Bookflix readers"
        items.append(Recommendation(book_id=int(model.book_ids[i]), score=float(scores[i]),
                                    reason=reason, strategy=CONTENT_SIMILARITY))
    return items, CONTENT_SIMILARITY
