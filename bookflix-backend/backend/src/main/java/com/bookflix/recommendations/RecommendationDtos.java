package com.bookflix.recommendations;

import java.util.List;

import com.bookflix.books.BookDtos.BookSummaryDto;

public final class RecommendationDtos {

    private RecommendationDtos() {
    }

    public record RecommendationItemDto(BookSummaryDto book, double score, String reason, String strategy) {
    }

    /**
     * @param shelf       FOR_YOU | BECAUSE_YOU_LIKED | SIMILAR | TRENDING | BY_GENRES
     * @param strategy    dominant strategy of the shelf (see docs/api/contracts.md)
     * @param degraded    true when the ML service was unavailable and SQL fallback ranking was used
     * @param modelVersion ML model version that produced the ranking, or null for SQL-ranked shelves
     * @param sourceBook  anchor book for SIMILAR / BECAUSE_YOU_LIKED, else null
     */
    public record RecommendationResponse(String shelf, String strategy, boolean degraded, String modelVersion,
                                         BookSummaryDto sourceBook, List<RecommendationItemDto> items) {
    }
}
