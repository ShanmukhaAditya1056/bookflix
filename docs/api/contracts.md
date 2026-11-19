# Bookflix REST API Contract (v1)

**Shared source of truth** between the backend (Claude Code) and the frontend (Codex).
Any change to a path, parameter, field, enum or status code must be made here first and called out in
the change log at the bottom.

- Base URL: `VITE_API_BASE_URL` (local default `http://localhost:8080`). All paths below start with `/api`.
- JSON everywhere, `Content-Type: application/json`. Field names are **camelCase**.
- Timestamps are ISO-8601 UTC strings, e.g. `"2026-11-19T12:30:52.893Z"`.
- IDs are integers (JSON numbers).
- Live OpenAPI: `GET /v3/api-docs`, Swagger UI at `/swagger-ui.html`.

## Authentication

Stateless **JWT bearer tokens** (HS256).

- Obtain one from `POST /api/auth/register` or `POST /api/auth/login`.
- Send it as `Authorization: Bearer <accessToken>`.
- Tokens expire after `expiresIn` seconds (default 7200). There is no refresh endpoint: on `401`, clear the
  session and send the user to login.
- No cookies are used, so no CSRF token is needed. Keep the token in memory, or in `sessionStorage`
  if you accept the XSS trade-off. Never log it.
- A **malformed or expired token on a public endpoint still returns `401`**. Drop a stale token
  before calling public endpoints.
- `POST /api/auth/login` and `/register` are rate-limited per client IP (default 10 per minute). Exceeding
  the limit returns `429 RATE_LIMITED` with a `Retry-After` header in seconds.

### Enums

| Enum | Values |
|---|---|
| `Role` | `READER`, `ADMIN` |
| `ShelfStatus` | `WANT_TO_READ`, `READING`, `COMPLETED` |
| `BookSort` (query) | `title` (default), `newest`, `year_desc`, `year_asc`, `rating`, `popular` |

## Error payload (every non-2xx response)

```json
{
  "code": "VALIDATION_ERROR",
  "message": "Request validation failed",
  "details": [{ "field": "email", "message": "must be a well-formed email address" }],
  "timestamp": "2026-11-19T12:30:52.893Z",
  "path": "/api/auth/register"
}
```

`details` is always an array, empty unless the error is field-level.

| HTTP | `code` values |
|---|---|
| 400 | `VALIDATION_ERROR`, `BAD_REQUEST`, `MALFORMED_REQUEST` |
| 401 | `UNAUTHORIZED` (missing, invalid or expired token), `INVALID_CREDENTIALS` (login) |
| 403 | `FORBIDDEN` (authenticated but wrong role) |
| 404 | `NOT_FOUND` |
| 405 | `METHOD_NOT_ALLOWED` |
| 409 | `CONFLICT`, `EMAIL_ALREADY_EXISTS`, `REBUILD_IN_PROGRESS` |
| 413 | `PAYLOAD_TOO_LARGE` (bodies over 64 KB) |
| 415 | `UNSUPPORTED_MEDIA_TYPE` |
| 429 | `RATE_LIMITED` |
| 503 | `ML_UNAVAILABLE` (admin rebuild only; ordinary recommendations never fail because of ML) |
| 500 | `INTERNAL_ERROR` |

## Shared shapes

```ts
type GenreDto = { id: number; name: string; slug: string };
type AuthorRef = { id: number; name: string };
type AuthorDto = { id: number; name: string; bio: string | null; createdAt: string };

type UserDto = {
  id: number; name: string; email: string; role: "READER" | "ADMIN";
  onboardingCompleted: boolean; genres: GenreDto[]; createdAt: string;
};

type BookSummaryDto = {
  id: number; title: string; author: AuthorRef; coverUrl: string | null;  // null => use placeholder art
  publishedYear: number | null; genres: GenreDto[];
  averageRating: number;   // 0..5, 2 dp; 0 when ratingCount = 0
  ratingCount: number; likeCount: number;
};

type MyBookState = {       // caller's own state; whole object is null for anonymous callers
  liked: boolean; rating: number | null; shelfStatus: ShelfStatus | null; progress: number | null;
};

type BookDetailDto = BookSummaryDto & {
  description: string | null; isbn: string | null; language: string;
  createdAt: string; updatedAt: string; me: MyBookState | null;
};

type PageResponse<T> = { items: T[]; page: number; size: number; totalElements: number; totalPages: number };
```

## Auth & users

| Method & path | Auth | Body | Success |
|---|---|---|---|
| `POST /api/auth/register` | public | `{ name (1..100), email, password (8..72) }` | `201 AuthResponse` |
| `POST /api/auth/login` | public | `{ email, password }` | `200 AuthResponse` |
| `GET /api/users/me` | reader | – | `200 UserDto` |
| `PUT /api/users/me` | reader | `{ name (1..100) }` | `200 UserDto` |
| `PUT /api/users/me/genres` | reader | `{ genreIds: number[] }` (max 20) | `200 UserDto` |
| `GET /api/users/me/stats` | reader | – | `200 UserStatsDto` |

```ts
type AuthResponse = { accessToken: string; tokenType: "Bearer"; expiresIn: number; user: UserDto };
type UserStatsDto = {
  likedCount: number; reviewCount: number; averageRatingGiven: number | null;
  bookshelf: { WANT_TO_READ: number; READING: number; COMPLETED: number };
};
```

- Registration always creates a `READER`; any `role` field in the body is ignored. Emails are lower-cased.
  A duplicate email returns `409 EMAIL_ALREADY_EXISTS`.
- **Onboarding:** route to genre onboarding when `user.onboardingCompleted === false`.
  `PUT /api/users/me/genres` sets `onboardingCompleted = true`. **Skip** = send `{ "genreIds": [] }`.
  Unknown genre ids return `400 BAD_REQUEST`.

## Catalog (public; a token, if sent, personalises `me`)

| Method & path | Success |
|---|---|
| `GET /api/books?page=0&size=20&q=&genreId=&sort=title` | `200 PageResponse<BookSummaryDto>` |
| `GET /api/books/{id}` | `200 BookDetailDto` / `404` |
| `GET /api/genres` | `200 GenreDto[]` (sorted by name) |
| `GET /api/authors?q=` | `200 AuthorDto[]` (sorted by name; `q` = name contains) |

- `page` ≥ 0 (default 0), `size` 1..50 (default 20). Out-of-range values return `400 VALIDATION_ERROR`.
- `q` (max 100 chars) searches title, author name and description. Every word must match, and words
  are prefix-matched (`"vampir"` finds "vampire"). Words of 2 characters or fewer and common stop words
  are ignored. If nothing is left, `q` becomes a title prefix match.
- `genreId` filters to books that have that genre. An unknown `sort` value returns `400 BAD_REQUEST`.
- Soft-deleted books never appear, and `GET /api/books/{id}` returns `404` for them.

## Likes & reviews

| Method & path | Auth | Body | Success |
|---|---|---|---|
| `POST /api/books/{id}/like` | reader | – | `200 LikeStatusDto` (idempotent) |
| `DELETE /api/books/{id}/like` | reader | – | `200 LikeStatusDto` (idempotent) |
| `GET /api/books/{id}/reviews?page=0&size=10` | public | – | `200 PageResponse<ReviewDto>`, newest first |
| `POST /api/books/{id}/reviews` | reader | `{ rating: 1..5, comment?: string (≤2000) }` | `201` created / `200` updated: `ReviewDto` |
| `DELETE /api/books/{id}/reviews/me` | reader | – | `204` (idempotent) |

```ts
type LikeStatusDto = { bookId: number; liked: boolean; likeCount: number };
type ReviewDto = {
  id: number; bookId: number; user: { id: number; name: string };  // no email exposed
  rating: number; comment: string | null; createdAt: string; updatedAt: string;
};
```

Each user has at most one review per book. A second `POST` updates it. After a write, refetch the book
for the new `averageRating` and `ratingCount`.

## Bookshelf (always the caller's own shelf)

| Method & path | Body | Success |
|---|---|---|
| `GET /api/bookshelf?status=` | – | `200 BookshelfEntryDto[]`, most recently updated first |
| `PUT /api/bookshelf/{bookId}` | `{ status: ShelfStatus, progress?: 0..100 }` | `200 BookshelfEntryDto` (upsert) |
| `DELETE /api/bookshelf/{bookId}` | – | `204` (idempotent) |

```ts
type BookshelfEntryDto = { bookId: number; book: BookSummaryDto; status: ShelfStatus; progress: number; updatedAt: string };
```

- `COMPLETED` always stores `progress = 100`. If `progress` is omitted, the previous value is kept
  (0 for a new entry).
- **Continue Reading** = `GET /api/bookshelf?status=READING`. Show that shelf only when it is non-empty.

## Recommendations

Every shelf returns the same envelope:

```ts
type RecommendationResponse = {
  shelf: "FOR_YOU" | "BECAUSE_YOU_LIKED" | "SIMILAR" | "TRENDING" | "BY_GENRES";
  strategy: string;            // see table below
  degraded: boolean;           // true => ML unavailable, simpler fallback ranking was used
  modelVersion: string | null; // set when the ML model produced the ranking
  sourceBook: BookSummaryDto | null;  // anchor for SIMILAR / BECAUSE_YOU_LIKED
  items: { book: BookSummaryDto; score: number /* 0..1 */; reason: string; strategy: string }[];
};
```

| Method & path | Auth | Notes |
|---|---|---|
| `GET /api/recommendations/for-you?limit=12` | reader | Personalised. Never includes books the user liked, rated or shelved. |
| `GET /api/recommendations/because-you-liked?limit=12` | reader | Anchored on the user's most recent like (else latest rating ≥ 4). `items: []` and `sourceBook: null` if there is none: hide the shelf. Item `reason` = `"Because you liked <title>"`. |
| `GET /api/recommendations/genres?limit=12` | reader | "Based on Your Genres". Empty when the user has no genres or signals. |
| `GET /api/recommendations/similar/{bookId}?limit=12` | public | For the book detail page. `404` if the book doesn't exist. |
| `GET /api/recommendations/trending?limit=12` | public | Recent likes, reviews and shelf activity (30 days), then all-time popularity. Good for the landing page. |

- `limit` is 1..50 (default 12).
- `strategy` values: `CONTENT_HYBRID`, `GENRE_POPULARITY`, `POPULARITY`, `CONTENT_SIMILARITY` (ML);
  `FALLBACK_GENRE_POPULARITY`, `FALLBACK_POPULARITY`, `FALLBACK_METADATA_SIMILARITY` (ML down);
  `TRENDING`, `GENRE_MATCH` (SQL-owned shelves); `NONE` (nothing to anchor on).
- Show `reason` as the "why this book" text. When `degraded` is true, a subtle "basic recommendations"
  hint is fine. It is never an error.
- An ML outage never turns ordinary recommendation calls into `5xx`.

## Admin (role `ADMIN`; readers get `403`, anonymous callers get `401`)

| Method & path | Body | Success |
|---|---|---|
| `POST /api/admin/books` | `BookRequest` | `201 BookDetailDto` |
| `PUT /api/admin/books/{id}` | `BookRequest` | `200 BookDetailDto` |
| `DELETE /api/admin/books/{id}` | – | `204` (soft delete; see deletion policy) |
| `POST /api/admin/authors` / `PUT /api/admin/authors/{id}` | `{ name (1..200), bio? (≤5000) }` | `201` / `200 AuthorDto` |
| `DELETE /api/admin/authors/{id}` | – | `204`, or `409 CONFLICT` if any book references the author |
| `POST /api/admin/genres` / `PUT /api/admin/genres/{id}` | `{ name (1..80), slug? (kebab-case; derived from name if omitted) }` | `201` / `200 GenreDto`; `409` on a duplicate name or slug |
| `DELETE /api/admin/genres/{id}` | – | `204`, or `409 CONFLICT` if an active book still uses the genre |
| `GET /api/admin/stats` | – | `200 AdminStatsDto` |
| `POST /api/admin/recommendations/rebuild` | – | `200 RebuildResult`; `409 REBUILD_IN_PROGRESS`; `503 ML_UNAVAILABLE` |

```ts
type BookRequest = {
  title: string;                 // 1..255
  description?: string | null;   // ≤ 10000
  authorId: number;
  isbn?: string | null;          // ISBN-10/13, hyphens allowed; stored without hyphens; unique
  coverUrl?: string | null;      // http(s) URL, ≤ 512
  publishedYear?: number | null; // -3000..2100
  language?: string;             // default "en"
  genreIds: number[];            // 1..10 existing genre ids
};
type AdminStatsDto = {
  users: number; readers: number; admins: number; books: number; deletedBooks: number;
  authors: number; genres: number; reviews: number; likes: number; bookshelfEntries: number;
  model: { enabled: boolean; available: boolean; modelVersion: string | null; builtAt: string | null;
           bookCount: number | null; stale: boolean; lastError: string | null };
};
type RebuildResult = {
  status: "REBUILT"; modelVersion: string; bookCount: number; builtAt: string;
  evaluation: { status: "ok" | "insufficient_data"; [k: string]: unknown };  // display as-is
};
```

Admin list screens use the public reads: `GET /api/books`, `/api/authors` and `/api/genres`.

## Change log

| Date | Change |
|---|---|
| 2026-11-19 | Initial contract. Additions beyond the original endpoint list: `GET /api/users/me/stats`, `DELETE /api/books/{id}/reviews/me`, `GET /api/recommendations/because-you-liked`, `GET /api/recommendations/genres`. `similar` and `trending` are public. Books are soft-deleted. |
