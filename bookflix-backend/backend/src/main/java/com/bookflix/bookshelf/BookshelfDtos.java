package com.bookflix.bookshelf;

import java.time.Instant;

import com.bookflix.books.BookDtos.BookSummaryDto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public final class BookshelfDtos {

    private BookshelfDtos() {
    }

    public record BookshelfEntryDto(Long bookId, BookSummaryDto book, ShelfStatus status, int progress,
                                    Instant updatedAt) {
    }

    /**
     * progress is optional (0..100). Rules: COMPLETED forces progress=100; omitted progress keeps the
     * existing value (or 0 for a new entry).
     */
    public record BookshelfRequest(@NotNull ShelfStatus status, @Min(0) @Max(100) Integer progress) {
    }
}
