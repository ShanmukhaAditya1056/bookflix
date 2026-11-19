# Architecture

```
React (frontend/)  ──HTTPS/JSON──▶  Spring Boot API :8080  ──JDBC──▶  MySQL 8.4
                                         │
                                         └─HTTP + X-Internal-Token (private network)──▶  FastAPI recommendation-service :8000
                                                                                            └─ joblib artifacts (volume)
```

| Component | Location | Responsibility |
|---|---|---|
| Spring Boot API | `bookflix-backend/backend` | Auth (JWT), catalog, interactions, bookshelf, admin, Trending, recommendation orchestration and SQL fallback, scheduling model rebuilds |
| Recommendation service | `bookflix-backend/recommendation-service` | TF-IDF model training, persistence, scoring (`for-you`, `similar`), offline evaluation |
| Schema & seed | `backend/src/main/resources/db/{migration,seed}`, `bookflix-backend/database/seed` | Flyway V-migrations (schema) plus a repeatable seed generated from `catalog.json` |
| Shared contract | `docs/api/contracts.md` | Frontend ↔ backend API |

## Recommendation request flow

1. The client calls `GET /api/recommendations/for-you` with its JWT.
2. Spring takes the user id from the verified token. Ids in the query or body are never trusted.
3. `RecommendationQueries.loadSignals` reads preferred genres, likes, ratings and shelf entries for
   active books from MySQL.
4. `MlClient` posts the signals and exclusions to `/internal/recommendations/for-you`.
5. The response ids are joined to live `books` rows. Deleted, unknown, excluded and duplicate ids are
   dropped. A short list is topped up with SQL genre/popularity ranking.
6. On any ML failure (disabled, timeout, connection refused, non-2xx), Spring returns SQL fallback ranking
   with `degraded: true`, never a 5xx.

## Model lifecycle

- `ModelRebuildService` marks the model *stale* whenever books, authors or genres change. A scheduled check
  (every minute) rebuilds a stale model. It also rebuilds when the ML service reports no model or a
  different catalog fingerprint, which happens after an ML restart or a lost volume.
- A periodic refresh (every 6 hours) keeps popularity features current.
- Admins can force a rebuild synchronously. Spring guards against concurrent rebuilds with an
  `AtomicBoolean`, and the ML side uses a non-blocking lock (`409`).
- The ML service writes artifacts to a temp directory, `os.replace`s it into `versions/<version>`, then
  atomically swaps the `CURRENT` pointer and the in-memory reference. It keeps the last 3 versions.

## Known limitations

- The auth rate limiter and rebuild flags are in-memory. With several API instances, move them to a
  shared store such as Redis or a gateway.
- JWTs can't be revoked before they expire (2 hours). A role change takes effect on the next login.
- Recommendation event logging (`recommendation_events`, optional in the brief) is not implemented.
