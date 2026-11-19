# Recommendation engine

This is intentionally simple, explainable ML: **TF-IDF + cosine similarity, genre affinity and smoothed
popularity**. There is no deep learning, no collaborative filtering and no paid AI APIs.

## Documents (`app/training/documents.py`)
Each book becomes a document with:
- the title, repeated twice;
- the genres, repeated three times, as plain words plus a distinct token such as `genre_sciencefiction`;
- the author name plus a token such as `author_bramstoker`;
- the description.

HTML tags and entities are stripped, missing fields become empty strings, duplicate book ids keep the
last row, and duplicate genres collapse.

## Vectorisation (`app/training/builder.py`)
The model uses `TfidfVectorizer(stop_words="english", strip_accents="unicode", ngram_range=(1,2),
sublinear_tf=True, max_df=0.85 (n≥20), norm="l2")`.

`ModelArtifacts` is persisted with joblib. It holds the vectorizer, the sparse CSR matrix, the ordered
book ids, titles, a genre membership matrix, the popularity vector, the version, the catalog fingerprint
and the evaluation. Dense vectors are never stored in MySQL.

**Popularity** = `0.7 · log1p(2·likes + ratings + shelf) / max` + `0.3 · (bayesianRating − 1) / 4`.
The Bayesian rating uses a prior mean of 3.0 with weight 5, so a single 5★ rating can't dominate.

## Signals
| Signal | Weight |
|---|---|
| Like | +1.0 |
| Rating 5 / 4 | +1.0 / +0.6 |
| Shelf COMPLETED / READING | +0.8 / +0.6 |
| Shelf WANT_TO_READ | 0 (excluded from candidates, but not evidence of enjoyment) |
| Rating 1 / 2 | negative 1.0 / 0.6; overrides any positive signal for that book |

## Scoring (`app/recommenders/scoring.py`)
- **Established user** (has positive signals):
  `0.60·content + 0.30·genre + 0.10·popularity`.
  `content = clip(cos(book, positive profile) − 0.5·cos(book, negative profile))`.
- **Cold start** (only preferred genres): `0.75·genre + 0.25·popularity`.
- **No signals:** popularity only.
- **Similar:** `0.85·cos(book, source) + 0.10·genreJaccard + 0.05·popularity`.

Genre affinity = +1 for each preferred genre, plus the weights of positive books, minus 0.5 × the weights
of negative books, normalised to [0, 1]. A book's genre match is the highest affinity among its genres.

Scores are `nan_to_num`'d, clipped to [0, 1], rounded to 6 dp, and ranked by `(score desc, bookId asc)`,
so results are deterministic. Exclusions, interacted books and the source book are removed before
`offset`/`limit`.

**Reasons** pick the most specific explanation that is materially true:
1. "Similar to *X*", when raw similarity to a positive book is ≥ 0.10;
2. "Matches your interest in *Genre*";
3. "Popular with Bookflix readers".

## Shelves
| Shelf | Owner |
|---|---|
| For You | ML (`for-you`), with SQL fallback |
| Because You Liked X | Spring picks the anchor; ranking from ML `similar` |
| Similar | ML `similar`, with fallback on genre overlap and same author |
| Trending | **Spring SQL**, the canonical owner |
| Based on Your Genres | Spring SQL |
| Continue Reading | Direct bookshelf query (`status=READING`) |

## Offline evaluation (`app/evaluation/offline.py`)
Evaluation runs on every rebuild and is returned in the rebuild response.
- It needs ≥ 5 pseudonymous users with ≥ 4 positive books each. Otherwise it returns
  `status: "insufficient_data"` with counts and **no metrics**.
- For each user, 25% of positives are held out (seed 42) and the rest act as likes.
- It reports Precision@5, Recall@5 and catalog coverage for the model versus a popularity baseline.
- Popularity is recomputed from the training split only, so held-out items don't leak.

**Current status:** the seeded demo has no interactions, so metrics are **unavailable**. The end-to-end
run on 2026-11-19 reported `insufficient_data` (1 user, 0 eligible). No numbers have been published.
