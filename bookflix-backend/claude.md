# BOOKFLIX — Claude Code Backend & ML Implementation Instructions

## Mission
Build the **backend and recommendation engine** for **Bookflix**, a Netflix-inspired book discovery and personalized recommendation platform.
- **Backend Stack:** Java 21 LTS, Maven, compatible stable Spring Boot release, Spring Web, Spring Data JPA, Spring Validation, Spring Security, MySQL 8+, Flyway, springdoc OpenAPI, JUnit 5, Mockito, Testcontainers (when available)[cite: 1].
- **Recommendation Engine:** Python 3.11+ FastAPI, Uvicorn, scikit-learn, pandas, numpy, joblib, pytest[cite: 1]. Keep it intentionally **basic, explainable ML**: TF-IDF + cosine similarity combined with genre affinity and popularity[cite: 1].
- **Guardrails:** Do not silently upgrade to deep learning, collaborative filtering, paid LLM/AI APIs, or a microservice maze[cite: 1]. Deliver working code, not mock-only endpoints or hardcoded recommendations disguised as personalization[cite: 1]. Never expose internal ML service credentials or secrets in source control or frontend responses[cite: 1].

## Coordination with Frontend Agent (Codex)
Another agent (**Codex**) builds `frontend/` using `CODEX.md`[cite: 1]. Keep both sides aligned:
1. **Treat `docs/api/contracts.md` as the shared source of truth** for REST paths, query params, request/response JSON shapes, bookshelf statuses (`WANT_TO_READ`, `READING`, `COMPLETED`), roles (`READER`, `ADMIN`), and standard error payloads (`code`, `message`, `details`)[cite: 1]. Create and update it whenever a contract is defined or changed.
2. **Never break an agreed API contract silently.** If a field name, status enum, query parameter, or status code changes, update `docs/api/contracts.md` and clearly flag the change in your phase report so Codex can update its types/client.
3. **Stay inside backend/ML boundaries:** Focus on `backend/`, `recommendation-service/`, `database/`, `docs/`, `docker-compose.yml`, and `.env.example`[cite: 1]. Avoid modifying `frontend/` files unless resolving a shared contract/config issue and explicitly reporting it.

## Roles & Scope
- **READER:** Sign up/in, genre onboarding, browse/search/filter books, view book details, like/unlike, rate/review (1..5), manage bookshelf (`WANT_TO_READ`, `READING`, `COMPLETED`) and progress (0..100), edit profile/preferences, fetch recommendations[cite: 1].
- **ADMIN:** All reader capabilities plus CRUD for books, authors, and genres, platform counts/stats, and triggering model rebuilds—enforced on the backend via Spring Security, never UI-only[cite: 1].
- **Out of Scope:** Full copyrighted book hosting, payments, social feeds, live chat, LLM chatbots, Kafka, deep learning, advanced collaborative filtering[cite: 1].

## Architecture & Request Flow
`React Client -> Spring Boot REST API -> MySQL`[cite: 1]
For recommendations:
1. Authenticated client calls `/api/recommendations/...` on Spring Boot[cite: 1].
2. Spring resolves user identity from the authenticated principal (never trusting arbitrary user IDs from query/body)[cite: 1].
3. Spring loads trusted user preference/interaction features from MySQL and calls the private internal FastAPI service over the backend network (protected with a shared internal token)[cite: 1].
4. FastAPI ranks candidates using the persisted TF-IDF model + scoring rules and returns `{bookId, score, reason, strategy}`[cite: 1].
5. Spring joins book metadata, filters out deleted/disallowed items, and returns DTOs[cite: 1].
6. If FastAPI is down, times out, or fails, Spring gracefully falls back to genre/popularity ranking without returning a 500 error for ordinary recommendations[cite: 1].

## Database Schema (Flyway Migrations)
Use versioned Flyway migrations and idempotent seed scripts (genres, authors, 60–150 legally sourced or clearly labeled demo books, and a local-dev-only admin user; never ship default production admin credentials)[cite: 1]:
- `users(id PK, name, email UNIQUE, password_hash, role, onboarding_completed, created_at, updated_at)`[cite: 1]
- `authors(id PK, name, bio, created_at)`[cite: 1]
- `genres(id PK, name UNIQUE, slug UNIQUE)`[cite: 1]
- `books(id PK, title, description TEXT, author_id FK, isbn nullable UNIQUE, cover_url, published_year nullable, language, created_at, updated_at)`[cite: 1]
- `book_genres(book_id FK, genre_id FK, PRIMARY KEY(book_id, genre_id))`[cite: 1]
- `user_genres(user_id FK, genre_id FK, PRIMARY KEY(user_id, genre_id))`[cite: 1]
- `user_likes(user_id FK, book_id FK, created_at, PRIMARY KEY(user_id, book_id))`[cite: 1]
- `reviews(id PK, user_id FK, book_id FK, rating 1..5, comment TEXT, created_at, updated_at, UNIQUE(user_id, book_id))`[cite: 1]
- `bookshelf(id PK, user_id FK, book_id FK, status, progress 0..100, updated_at, UNIQUE(user_id, book_id))`[cite: 1]
- `recommendation_events(id PK, user_id FK, book_id FK, source_shelf, event_type, occurred_at)` (optional; keep PII minimal)[cite: 1]

Index search fields and foreign keys properly; query with indexed/filtered queries rather than loading the entire catalog into Java memory[cite: 1]. Define and test the deletion policy for books that have user interactions[cite: 1].

## Recommendation Engine (FastAPI + scikit-learn)
- **Documents:** Build text per book from title, repeated/weighted genres, author, and description; normalize missing fields, strip markup, deduplicate, and apply stop words[cite: 1].
- **Vectorization & Artifacts:** Fit `TfidfVectorizer` and persist vectorizer, sparse matrix, ordered book IDs, and model version/metadata via `joblib` (do not store huge dense vectors in MySQL)[cite: 1].
- **Signals & Scoring:** Build weighted positive profile from likes, ratings >= 4, and reading/completed; exclude or penalize ratings <= 2[cite: 1]. Combine content similarity, genre affinity, and smoothed popularity (e.g., `0.60 * content_similarity + 0.30 * genre_match + 0.10 * popularity` for established users; genre + popularity for cold start)[cite: 1].
- **Shelves & Exclusions:** Exclude disliked/disallowed books and source book in `similar`, deduplicate, support `topK`/pagination, return finite deterministic scores, support `Because You Liked X` and `Trending` (with one canonical owner), and keep `Continue Reading` as a direct bookshelf query[cite: 1].
- **Model Rebuild & Evaluation:** Support atomic artifact swaps on rebuild with safe concurrency and stale-model handling[cite: 1]. Include offline evaluation when enough interaction data exists (Precision@5, Recall@5, coverage vs popularity baseline) or honestly report metrics as unavailable without fabricating numbers[cite: 1].
- **Internal Endpoints:** `GET /health`, `POST /internal/recommendations/for-you`, `POST /internal/recommendations/similar`, `POST /internal/recommendations/trending` (or canonical Spring trending owner), `POST /internal/model/rebuild`[cite: 1].

## Spring Boot REST API & Security
- **Auth & Users:** `POST /api/auth/register`, `POST /api/auth/login`, `GET /api/users/me`, `PUT /api/users/me`, `PUT /api/users/me/genres`[cite: 1].
- **Catalog:** `GET /api/books?page=&size=&q=&genreId=&sort=`, `GET /api/books/{id}`, `GET /api/genres`, `GET /api/authors`[cite: 1].
- **Interactions:** `POST /api/books/{id}/like`, `DELETE /api/books/{id}/like`, `POST /api/books/{id}/reviews`, `GET /api/books/{id}/reviews`[cite: 1].
- **Bookshelf:** `GET /api/bookshelf?status=`, `PUT /api/bookshelf/{bookId}`, `DELETE /api/bookshelf/{bookId}`[cite: 1].
- **Recommendations:** `GET /api/recommendations/for-you?limit=`, `GET /api/recommendations/similar/{bookId}?limit=`, `GET /api/recommendations/trending?limit=`[cite: 1].
- **Admin:** CRUD under `/api/admin/books`, authors, and genres, `GET /api/admin/stats`, `POST /api/admin/recommendations/rebuild`[cite: 1].
- **Security:** BCrypt/Argon2 password hashing, documented JWT or HttpOnly-cookie + CSRF strategy, server-side role and ownership checks, restrictive CORS, auth rate limiting, request-size limits, Bean Validation, consistent error DTOs, OpenAPI docs, and `.env.example` with placeholders only[cite: 1].

## Backend Layout
```text
bookflix/
  backend/
    src/main/java/com/bookflix/{config,auth,users,books,genres,authors,reviews,bookshelf,recommendations,admin,common}/
    src/main/resources/db/migration/
    src/test/
  recommendation-service/
    app/{api,schemas,core,recommenders,training,evaluation}/
    tests/
  database/seed/
  docs/{architecture,api,ml,testing}/
  docker-compose.yml
  .env.example
```[cite: 1]

## Execution Phases & Reporting
Work in order: **Phase 0** (Inspect & Plan) -> **Phase 1** (Scaffold & Infrastructure) -> **Phase 2** (Schema & Auth) -> **Phase 3** (Catalog & Admin) -> **Phase 4** (Reader Interactions) -> **Phase 5** (ML Service) -> **Phase 6** (Java–ML Integration) -> **Phase 8** (Quality, Docs & Hardening)[cite: 1].
At the end of **each phase**, report: files added/changed, exact commands run, test results (pass/fail/not run—never claim a test passed unless executed), contract changes for Codex, remaining issues, and next phase[cite: 1].