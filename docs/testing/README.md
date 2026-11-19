# Testing

## Backend (JUnit 5, Mockito, Testcontainers MySQL 8.4)
Requires Docker. From `bookflix-backend/backend`:
```
./mvnw test
```
| Suite | Covers |
|---|---|
| `AuthIntegrationTest` | Register/login, role escalation ignored, duplicate email, validation/malformed/413 errors, 401 shape, profile and onboarding |
| `CatalogIntegrationTest` | Paging, FULLTEXT search (title/author/prefix/multi-word/short), genre filter, sorting, 400s, detail and 404 |
| `AdminIntegrationTest` | 403/401 for non-admins, CRUD for books/authors/genres, search sync after author rename, 409 rules, soft-delete policy |
| `InteractionsIntegrationTest` | Idempotent likes and counts, review upsert, aggregates, validation, bookshelf rules, ownership isolation, stats |
| `RecommendationFallbackIntegrationTest` | With ML disabled: cold start, exclusions, because-you-liked, similar, trending, by-genres |
| `RecommendationServiceTest` | ML results joined, filtered (deleted/excluded/duplicate/NaN), fallback on ML failure |
| `AuthRateLimitFilterTest` | Sliding-window limit, 429 payload, recovery |

## Recommendation service (pytest)
From `bookflix-backend/recommendation-service`:
```
python -m venv .venv && .venv/Scripts/pip install -r requirements-dev.txt   # POSIX: .venv/bin/pip
.venv/Scripts/python -m pytest
```
Covers document building, model build, popularity smoothing, every scoring branch, exclusions,
determinism and tie-breaking, pagination, negative feedback, evaluation (both honest-unavailable and
computed), the API contract, token enforcement, persistence and reload, corrupt artifacts, atomic swap,
pruning and concurrent rebuilds.

## End to end
```
cp .env.example .env    # fill in values; set BOOKFLIX_DEV_ADMIN_ENABLED=true for admin checks
docker compose up -d --build
BOOKFLIX_API=http://localhost:8080 BOOKFLIX_DEV_ADMIN_EMAIL=... BOOKFLIX_DEV_ADMIN_PASSWORD=... \
  python bookflix-backend/scripts/smoke_e2e.py
```
