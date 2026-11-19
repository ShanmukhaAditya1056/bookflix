# Deletion policy

| Entity | Policy | Rationale |
|---|---|---|
| **Book** | Always **soft-deleted** (`books.deleted_at`). It is hidden from the catalog, search, detail (`404`), bookshelf lists, user stats, recommendations (ML candidates and joins), Trending and SQL fallbacks. Likes, reviews and shelf rows are **kept**; new likes, reviews or shelf updates return `404`. The model is marked stale and rebuilt without the book. | Keeps reader history and audit data, avoids cascading deletes of user content, and keeps the model consistent. |
| **Author** | Hard delete only when **no** book (active or soft-deleted) references it; otherwise `409 CONFLICT`. | `books.author_id` is `ON DELETE RESTRICT`. |
| **Genre** | Hard delete only when no **active** book uses it; otherwise `409 CONFLICT`. Deleting it cascades the `book_genres` rows of soft-deleted books and any `user_genres` preferences. | Preferences that point at a removed genre carry no meaning. |
| **User** | No delete endpoint yet. FKs cascade (`ON DELETE CASCADE`) to likes, reviews, shelf and genres if a user is removed in SQL. | Account deletion is out of scope for v1. |

Tests: `AdminIntegrationTest.deletingABookWithInteractionsSoftDeletesIt` and the `409` assertions in
`AdminIntegrationTest.adminCrudForGenresAuthorsAndBooks`.

Soft-deleted books keep their ISBN, so re-creating a book with the same ISBN returns `409`.
