import math

import pytest

from app.recommenders import scoring
from app.schemas import ForYouRequest, RatingSignal, ShelfSignal, SimilarRequest
from app.training.builder import build_model


def ids(items):
    return [i.book_id for i in items]


def test_established_user_gets_content_hybrid_and_similar_books(model):
    items, strategy = scoring.for_you(model, ForYouRequest(liked_book_ids=[1], limit=3))
    assert strategy == scoring.CONTENT_HYBRID
    assert ids(items)[0] == 2  # Carmilla: the other vampire / horror book
    assert 1 not in ids(items)
    assert items[0].reason == "Similar to Dracula"
    assert all(i.strategy == scoring.CONTENT_HYBRID for i in items)


def test_cold_start_with_preferred_genres(model):
    items, strategy = scoring.for_you(model, ForYouRequest(preferred_genre_ids=[2], limit=2))
    assert strategy == scoring.GENRE_POPULARITY
    assert set(ids(items)) == {3, 4}
    assert all(i.reason == "Matches your interest in Romance" for i in items)


def test_no_signals_falls_back_to_popularity(model):
    items, strategy = scoring.for_you(model, ForYouRequest(limit=1))
    assert strategy == scoring.POPULARITY
    assert ids(items) == [3]  # most liked + best rated
    assert items[0].reason == "Popular with Bookflix readers"


def test_exclusions_and_interacted_books_are_never_returned(model):
    req = ForYouRequest(liked_book_ids=[1], ratings=[RatingSignal(book_id=5, rating=5)],
                        shelf=[ShelfSignal(book_id=7, status="WANT_TO_READ")], exclude_book_ids=[2], limit=10)
    items, _ = scoring.for_you(model, req)
    assert not {1, 2, 5, 7} & set(ids(items))
    assert len(ids(items)) == len(set(ids(items)))


def test_low_ratings_are_penalised_not_treated_as_positive(model):
    liked_only, _ = scoring.for_you(model, ForYouRequest(liked_book_ids=[6], limit=8))
    with_dislike, _ = scoring.for_you(model, ForYouRequest(liked_book_ids=[6],
                                                           ratings=[RatingSignal(book_id=1, rating=1)], limit=8))
    score_before = {i.book_id: i.score for i in liked_only}
    score_after = {i.book_id: i.score for i in with_dislike}
    assert 1 not in score_after
    assert score_after[2] < score_before[2]  # Carmilla is close to the disliked Dracula

    only_negative, strategy = scoring.for_you(model, ForYouRequest(ratings=[RatingSignal(book_id=1, rating=2)], limit=3))
    assert strategy == scoring.POPULARITY
    assert 1 not in ids(only_negative)


def test_want_to_read_alone_is_not_a_positive_signal(model):
    _, strategy = scoring.for_you(model, ForYouRequest(shelf=[ShelfSignal(book_id=1, status="WANT_TO_READ")]))
    assert strategy == scoring.POPULARITY


def test_scores_are_finite_bounded_and_deterministic(model):
    req = ForYouRequest(liked_book_ids=[3], preferred_genre_ids=[4], limit=10)
    first, _ = scoring.for_you(model, req)
    second, _ = scoring.for_you(model, req)
    assert [(i.book_id, i.score) for i in first] == [(i.book_id, i.score) for i in second]
    assert all(math.isfinite(i.score) and 0 <= i.score <= 1 for i in first)
    scores = [i.score for i in first]
    assert scores == sorted(scores, reverse=True)


def test_ties_break_by_book_id(catalog):
    for b in catalog:
        b.like_count, b.rating_count, b.avg_rating = 0, 0, 0.0
    flat = build_model(__import__("app.schemas", fromlist=["RebuildRequest"]).RebuildRequest(
        catalog_fingerprint="flat", books=catalog))
    items, _ = scoring.for_you(flat, ForYouRequest(limit=8))
    assert ids(items) == sorted(ids(items))


def test_pagination_with_offset(model):
    full, _ = scoring.for_you(model, ForYouRequest(liked_book_ids=[1], limit=6))
    page2, _ = scoring.for_you(model, ForYouRequest(liked_book_ids=[1], limit=3, offset=3))
    assert ids(page2) == ids(full)[3:6]


def test_unknown_ids_in_signals_are_ignored(model):
    items, strategy = scoring.for_you(model, ForYouRequest(liked_book_ids=[12345], limit=3))
    assert strategy == scoring.POPULARITY
    assert len(items) == 3


def test_similar_excludes_source_and_prefers_same_themes(model):
    items, strategy = scoring.similar(model, SimilarRequest(book_id=3, limit=3))
    assert strategy == scoring.CONTENT_SIMILARITY
    assert 3 not in ids(items)
    assert ids(items)[0] == 4  # the other Austen romance
    assert items[0].reason == "Similar themes to Pride and Prejudice"


def test_similar_respects_exclusions_and_unknown_book(model):
    items, _ = scoring.similar(model, SimilarRequest(book_id=3, exclude_book_ids=[4], limit=8))
    assert 4 not in ids(items) and 3 not in ids(items)
    with pytest.raises(scoring.UnknownBookError):
        scoring.similar(model, SimilarRequest(book_id=999))


def test_seed_catalog_similarity_is_sensible(seed_request):
    model = build_model(seed_request)
    items, _ = scoring.similar(model, SimilarRequest(book_id=16, limit=5))  # Hound of the Baskervilles
    assert {15, 17} & set(ids(items))  # other Holmes books
    fy, _ = scoring.for_you(model, ForYouRequest(liked_book_ids=[7], limit=5))  # Dracula
    assert 82 in ids(fy)  # Carmilla
