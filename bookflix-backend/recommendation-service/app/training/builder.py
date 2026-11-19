"""Fit the TF-IDF model and derive per-book genre and popularity features."""
from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any

import numpy as np
import scipy.sparse as sp
from sklearn.feature_extraction.text import TfidfVectorizer

from app.schemas import RebuildRequest
from app.training.documents import catalog_frame

FORMAT_VERSION = 1

# Bayesian rating prior: a book with few ratings is pulled towards a neutral 3/5.
RATING_PRIOR_MEAN = 3.0
RATING_PRIOR_WEIGHT = 5.0


class EmptyCatalogError(ValueError):
    pass


@dataclass
class ModelArtifacts:
    format_version: int
    model_version: str
    built_at: str
    catalog_fingerprint: str
    book_ids: np.ndarray                 # int64, sorted ascending
    titles: list[str]
    book_genres: list[tuple[int, ...]]
    genre_names: dict[int, str]
    vectorizer: TfidfVectorizer
    matrix: sp.csr_matrix                # L2-normalised TF-IDF rows aligned with book_ids
    genre_matrix: sp.csr_matrix          # books x genre_index binary membership
    genre_index: dict[int, int]          # genre id -> column in genre_matrix
    popularity: np.ndarray               # smoothed popularity in [0, 1]
    evaluation: dict[str, Any] = field(default_factory=dict)
    index: dict[int, int] = field(default_factory=dict)

    def __post_init__(self) -> None:
        if not self.index:
            self.index = {int(b): i for i, b in enumerate(self.book_ids)}

    @property
    def book_count(self) -> int:
        return len(self.book_ids)


def smoothed_popularity(like_count: np.ndarray, rating_count: np.ndarray, avg_rating: np.ndarray,
                        shelf_count: np.ndarray) -> np.ndarray:
    """0.7 * log-scaled engagement + 0.3 * Bayesian-averaged rating, both scaled to [0, 1]."""
    engagement = np.log1p(2.0 * like_count + rating_count + shelf_count)
    max_eng = engagement.max() if engagement.size else 0.0
    eng_norm = engagement / max_eng if max_eng > 0 else np.zeros_like(engagement)
    bayes = (RATING_PRIOR_WEIGHT * RATING_PRIOR_MEAN + avg_rating * rating_count) / (RATING_PRIOR_WEIGHT + rating_count)
    quality = (bayes - 1.0) / 4.0
    pop = 0.7 * eng_norm + 0.3 * quality
    return np.clip(np.nan_to_num(pop, nan=0.0, posinf=1.0, neginf=0.0), 0.0, 1.0)


def build_model(req: RebuildRequest, now: datetime | None = None) -> ModelArtifacts:
    df = catalog_frame(req.books)
    if df.empty:
        raise EmptyCatalogError("catalog is empty")

    n = len(df)
    vectorizer = TfidfVectorizer(
        stop_words="english",
        strip_accents="unicode",
        lowercase=True,
        ngram_range=(1, 2),
        sublinear_tf=True,
        min_df=1,
        max_df=0.85 if n >= 20 else 1.0,
        norm="l2",
    )
    try:
        matrix = vectorizer.fit_transform(df["document"].tolist()).tocsr()
    except ValueError:
        # Only stop words / empty text in every document: fall back to an all-zero matrix.
        vectorizer = TfidfVectorizer(lowercase=True, token_pattern=r"(?u)\b\w+\b")
        matrix = vectorizer.fit_transform([d or "empty" for d in df["document"].tolist()]).tocsr()

    genre_names: dict[int, str] = {}
    for ids, names in zip(df["genre_ids"], df["genre_names"]):
        for gid, name in zip(ids, names):
            genre_names.setdefault(int(gid), name)
    genre_index = {gid: i for i, gid in enumerate(sorted(genre_names))}
    rows, cols = [], []
    for r, ids in enumerate(df["genre_ids"]):
        for gid in ids:
            rows.append(r)
            cols.append(genre_index[int(gid)])
    genre_matrix = sp.csr_matrix((np.ones(len(rows)), (rows, cols)), shape=(n, max(1, len(genre_index))))

    popularity = smoothed_popularity(df["like_count"].to_numpy(float), df["rating_count"].to_numpy(float),
                                     df["avg_rating"].to_numpy(float), df["shelf_count"].to_numpy(float))

    built = (now or datetime.now(timezone.utc)).astimezone(timezone.utc)
    version = f"{built:%Y%m%dT%H%M%S%fZ}-{req.catalog_fingerprint[:8] or 'nofp'}"
    return ModelArtifacts(
        format_version=FORMAT_VERSION,
        model_version=version,
        built_at=built.isoformat().replace("+00:00", "Z"),
        catalog_fingerprint=req.catalog_fingerprint,
        book_ids=df["id"].to_numpy(np.int64),
        titles=df["title"].tolist(),
        book_genres=[tuple(int(g) for g in ids) for ids in df["genre_ids"]],
        genre_names=genre_names,
        vectorizer=vectorizer,
        matrix=matrix,
        genre_matrix=genre_matrix,
        genre_index=genre_index,
        popularity=popularity,
    )
