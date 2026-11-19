"""Turn catalog rows into TF-IDF documents.

Each document = title (x2) + genres (x3, as words and a distinct genre token) + author (name and a
distinct author token) + description. Markup is stripped, missing fields become empty strings, and
duplicate book ids / genres are collapsed.
"""
from __future__ import annotations

import html
import re
from collections.abc import Iterable

import pandas as pd

from app.schemas import CatalogBook

TITLE_WEIGHT = 2
GENRE_WEIGHT = 3

_TAG_RE = re.compile(r"<[^>]+>")
_WS_RE = re.compile(r"\s+")
_NON_ALNUM = re.compile(r"[^a-z0-9]+")


def clean_text(value: str | None) -> str:
    if not value:
        return ""
    text = html.unescape(str(value))
    text = _TAG_RE.sub(" ", text)
    return _WS_RE.sub(" ", text).strip()


def _token(prefix: str, name: str) -> str:
    slug = _NON_ALNUM.sub("", name.lower())
    return f"{prefix}_{slug}" if slug else ""


def build_document(title: str, author: str, genre_names: Iterable[str], description: str) -> str:
    genres = [g for g in dict.fromkeys(clean_text(g) for g in genre_names) if g]
    genre_part = " ".join(f"{g} {_token('genre', g)}" for g in genres)
    author = clean_text(author)
    author_part = f"{author} {_token('author', author)}" if author else ""
    parts = [clean_text(title)] * TITLE_WEIGHT + [genre_part] * GENRE_WEIGHT + [author_part, clean_text(description)]
    return _WS_RE.sub(" ", " ".join(p for p in parts if p)).strip()


def catalog_frame(books: list[CatalogBook]) -> pd.DataFrame:
    """Normalised, de-duplicated (last row per id wins), id-sorted catalog DataFrame."""
    rows = []
    for b in books:
        genre_ids = list(dict.fromkeys(g.id for g in b.genres))
        genre_names = {g.id: clean_text(g.name) for g in b.genres}
        rows.append({
            "id": int(b.id),
            "title": clean_text(b.title),
            "author": clean_text(b.author),
            "description": clean_text(b.description),
            "genre_ids": tuple(genre_ids),
            "genre_names": tuple(genre_names[g] for g in genre_ids),
            "like_count": int(b.like_count),
            "rating_count": int(b.rating_count),
            "avg_rating": float(b.avg_rating),
            "shelf_count": int(b.shelf_count),
        })
    columns = ["id", "title", "author", "description", "genre_ids", "genre_names",
               "like_count", "rating_count", "avg_rating", "shelf_count"]
    df = pd.DataFrame(rows, columns=columns)
    if df.empty:
        return df
    df = df.drop_duplicates(subset="id", keep="last").sort_values("id").reset_index(drop=True)
    df["document"] = [build_document(r.title, r.author, r.genre_names, r.description) for r in df.itertuples()]
    return df
