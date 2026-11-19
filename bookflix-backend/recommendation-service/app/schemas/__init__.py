"""Wire schemas shared with the Spring backend (camelCase JSON). See docs/api/internal-ml.md."""
from __future__ import annotations

from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel


class CamelModel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, extra="ignore",
                              protected_namespaces=())


ShelfStatus = Literal["WANT_TO_READ", "READING", "COMPLETED"]
MAX_IDS = 5000


class RatingSignal(CamelModel):
    book_id: int
    rating: int = Field(ge=1, le=5)


class ShelfSignal(CamelModel):
    book_id: int
    status: ShelfStatus


class ForYouRequest(CamelModel):
    preferred_genre_ids: list[int] = Field(default_factory=list, max_length=200)
    liked_book_ids: list[int] = Field(default_factory=list, max_length=MAX_IDS)
    ratings: list[RatingSignal] = Field(default_factory=list, max_length=MAX_IDS)
    shelf: list[ShelfSignal] = Field(default_factory=list, max_length=MAX_IDS)
    exclude_book_ids: list[int] = Field(default_factory=list, max_length=MAX_IDS)
    limit: int = Field(default=12, ge=1, le=100)
    offset: int = Field(default=0, ge=0, le=1000)


class SimilarRequest(CamelModel):
    book_id: int
    exclude_book_ids: list[int] = Field(default_factory=list, max_length=MAX_IDS)
    limit: int = Field(default=12, ge=1, le=100)
    offset: int = Field(default=0, ge=0, le=1000)


class Recommendation(CamelModel):
    book_id: int
    score: float
    reason: str
    strategy: str


class RecommendationResponse(CamelModel):
    items: list[Recommendation]
    strategy: str
    model_version: str


class GenreRef(CamelModel):
    id: int
    name: str = Field(max_length=200)


class CatalogBook(CamelModel):
    id: int
    title: str = Field(max_length=500)
    description: str | None = Field(default=None, max_length=20000)
    author: str | None = Field(default=None, max_length=500)
    genres: list[GenreRef] = Field(default_factory=list, max_length=50)
    language: str | None = None
    published_year: int | None = None
    like_count: int = Field(default=0, ge=0)
    rating_count: int = Field(default=0, ge=0)
    avg_rating: float = Field(default=0.0, ge=0, le=5)
    shelf_count: int = Field(default=0, ge=0)


class InteractionSet(CamelModel):
    user: int
    positives: list[int] = Field(default_factory=list, max_length=MAX_IDS)


class RebuildRequest(CamelModel):
    catalog_fingerprint: str = Field(max_length=128)
    books: list[CatalogBook] = Field(max_length=100_000)
    interactions: list[InteractionSet] = Field(default_factory=list, max_length=1_000_000)


class RebuildResponse(CamelModel):
    model_version: str
    book_count: int
    built_at: str
    catalog_fingerprint: str
    evaluation: dict[str, Any]


class HealthResponse(CamelModel):
    status: str
    model_loaded: bool
    model_version: str | None = None
    built_at: str | None = None
    book_count: int | None = None
    catalog_fingerprint: str | None = None
