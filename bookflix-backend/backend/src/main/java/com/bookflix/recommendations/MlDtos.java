package com.bookflix.recommendations;

import java.util.List;
import java.util.Map;

/** Wire format of the private FastAPI service. See docs/api/internal-ml.md. */
public final class MlDtos {

    private MlDtos() {
    }

    public record RatingSignal(long bookId, int rating) {
    }

    public record ShelfSignal(long bookId, String status) {
    }

    public record ForYouRequest(List<Long> preferredGenreIds, List<Long> likedBookIds, List<RatingSignal> ratings,
                                List<ShelfSignal> shelf, List<Long> excludeBookIds, int limit, int offset) {
    }

    public record SimilarRequest(long bookId, List<Long> excludeBookIds, int limit, int offset) {
    }

    public record MlRecommendation(long bookId, double score, String reason, String strategy) {
    }

    public record MlRecommendationResponse(List<MlRecommendation> items, String strategy, String modelVersion) {
    }

    public record GenreRef(long id, String name) {
    }

    public record CatalogBook(long id, String title, String description, String author, List<GenreRef> genres,
                              String language, Integer publishedYear, int likeCount, int ratingCount,
                              double avgRating, int shelfCount) {
    }

    /** Positive book ids for one pseudonymous user (index only; no user ids or PII leave the backend). */
    public record InteractionSet(int user, List<Long> positives) {
    }

    public record RebuildRequest(String catalogFingerprint, List<CatalogBook> books, List<InteractionSet> interactions) {
    }

    public record RebuildResponse(String modelVersion, int bookCount, String builtAt, String catalogFingerprint,
                                  Map<String, Object> evaluation) {
    }

    public record HealthResponse(String status, boolean modelLoaded, String modelVersion, String builtAt,
                                 Integer bookCount, String catalogFingerprint) {
    }
}
