# Internal API: Spring Boot ↔ Recommendation Service

**Not for the frontend.** The FastAPI service sits on the private Docker network with no published
port. Every `/internal/*` call needs the header `X-Internal-Token: <BOOKFLIX_ML_INTERNAL_TOKEN>`.
If the token is missing or wrong the service returns `401`. If no token is configured it returns `503`
(fail closed).

JSON field names are camelCase.

| Endpoint | Request | Response |
|---|---|---|
| `GET /health` (no token) | – | `{status, modelLoaded, modelVersion, builtAt, bookCount, catalogFingerprint}` |
| `POST /internal/recommendations/for-you` | `{preferredGenreIds[], likedBookIds[], ratings[{bookId,rating}], shelf[{bookId,status}], excludeBookIds[], limit 1..100, offset 0..1000}` | `{items[{bookId,score,reason,strategy}], strategy, modelVersion}` |
| `POST /internal/recommendations/similar` | `{bookId, excludeBookIds[], limit, offset}` | same as above; `404` if the book is not in the model |
| `POST /internal/model/rebuild` | `{catalogFingerprint, books[{id,title,description,author,genres[{id,name}],language,publishedYear,likeCount,ratingCount,avgRating,shelfCount}], interactions[{user,positives[]}]}` | `{modelVersion, bookCount, builtAt, catalogFingerprint, evaluation}`; `409` if a rebuild is running; `422` if the catalog is empty |

Before any model exists, recommendation endpoints return `503 MODEL_NOT_READY`.

Ownership:
- **Spring owns identity and data.** It resolves the user from the JWT, loads the user's signals from MySQL
  and sends them in the request. The ML service never sees user ids, emails or names. Rebuild
  `interactions` use a per-snapshot sequential index in place of user ids.
- **Trending is owned by Spring** (SQL), so the ML service has no trending endpoint.
- Spring joins ids to live book rows, drops deleted or unknown ids, excluded books and duplicates,
  and falls back to SQL ranking on any ML error or timeout (connect 1 s, read 2 s; rebuild 60 s).
