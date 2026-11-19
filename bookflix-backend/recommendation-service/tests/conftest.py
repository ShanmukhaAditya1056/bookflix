from __future__ import annotations

import json
from pathlib import Path

import pytest

from app.schemas import CatalogBook, GenreRef, InteractionSet, RebuildRequest
from app.training.builder import build_model

SEED_CATALOG = Path(__file__).resolve().parents[2] / "database" / "seed" / "catalog.json"

HORROR, ROMANCE, SCIFI, MYSTERY = GenreRef(id=1, name="Horror"), GenreRef(id=2, name="Romance"), \
    GenreRef(id=3, name="Science Fiction"), GenreRef(id=4, name="Mystery")


def small_catalog() -> list[CatalogBook]:
    return [
        CatalogBook(id=1, title="Dracula", author="Bram Stoker", genres=[HORROR],
                    description="A <b>vampire</b> count travels to England; hunters pursue the undead.", like_count=10),
        CatalogBook(id=2, title="Carmilla", author="Sheridan Le Fanu", genres=[HORROR],
                    description="A languid vampire visitor at a lonely castle; girls sicken.", like_count=2),
        CatalogBook(id=3, title="Pride and Prejudice", author="Jane Austen", genres=[ROMANCE],
                    description="Courtship, marriage and first impressions among English gentry.", like_count=50,
                    rating_count=20, avg_rating=4.8),
        CatalogBook(id=4, title="Emma", author="Jane Austen", genres=[ROMANCE],
                    description="A matchmaker meddles with courtship and marriage in an English village.", like_count=5),
        CatalogBook(id=5, title="The Time Machine", author="H. G. Wells", genres=[SCIFI],
                    description="An inventor travels to the far future and meets the Eloi and Morlocks.", like_count=8),
        CatalogBook(id=6, title="The War of the Worlds", author="H. G. Wells", genres=[SCIFI, HORROR],
                    description="Martian invaders with heat-rays devastate England.", like_count=7),
        CatalogBook(id=7, title="A Study in Scarlet", author="Arthur Conan Doyle", genres=[MYSTERY],
                    description="A detective and a doctor investigate a London murder.", like_count=4),
        CatalogBook(id=8, title="The Hound of the Baskervilles", author="Arthur Conan Doyle",
                    genres=[MYSTERY, HORROR], description="A spectral hound haunts the moors; the detective investigates.",
                    like_count=6),
    ]


@pytest.fixture
def catalog() -> list[CatalogBook]:
    return small_catalog()


@pytest.fixture
def model(catalog):
    return build_model(RebuildRequest(catalog_fingerprint="abc123def", books=catalog))


@pytest.fixture
def seed_request() -> RebuildRequest:
    if not SEED_CATALOG.exists():
        pytest.skip("seed catalog not available")
    data = json.loads(SEED_CATALOG.read_text(encoding="utf-8"))
    genres = {g["slug"]: GenreRef(id=g["id"], name=g["name"]) for g in data["genres"]}
    authors = {a["id"]: a["name"] for a in data["authors"]}
    books = [CatalogBook(id=b["id"], title=b["title"], description=b["description"], author=authors[b["authorId"]],
                         genres=[genres[s] for s in b["genres"]], published_year=b.get("year"))
             for b in data["books"]]
    return RebuildRequest(catalog_fingerprint="seedfingerprint", books=books, interactions=[])


def interactions_for(users: list[list[int]]) -> list[InteractionSet]:
    return [InteractionSet(user=i, positives=p) for i, p in enumerate(users)]
