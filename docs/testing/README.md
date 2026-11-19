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

## Live contract verification
`bookflix-backend/scripts/verify_contract.py` checks every endpoint group in `docs/api/contracts.md`
against the running stack: response shapes, JWT/role/ownership rules, the error payload, and that
`for-you` re-ranks when genres, likes, ratings or shelf entries change. With `--fallback` it confirms
that every recommendation shelf returns `200` with `degraded: true` while ML is down.
```
set -a; . ./.env; set +a          # loads the dev admin credentials
BOOKFLIX_API=http://localhost:$BOOKFLIX_API_PORT python bookflix-backend/scripts/verify_contract.py
docker compose stop recommendation-service
BOOKFLIX_API=http://localhost:$BOOKFLIX_API_PORT python bookflix-backend/scripts/verify_contract.py --fallback
docker compose start recommendation-service
```
Each run makes up to 8 login/register calls. The auth rate limit is 10 per minute per IP, so wait about a
minute between runs or you'll see `429 RATE_LIMITED`.
