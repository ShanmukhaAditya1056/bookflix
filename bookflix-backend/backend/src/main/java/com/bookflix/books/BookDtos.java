package com.bookflix.books;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

import com.bookflix.authors.AuthorDtos.AuthorRef;
import com.bookflix.bookshelf.ShelfStatus;
import com.bookflix.genres.GenreDtos.GenreDto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class BookDtos {

    private BookDtos() {
    }

    /** Card-sized representation used in lists, shelves and recommendations. */
    public record BookSummaryDto(Long id, String title, AuthorRef author, String coverUrl, Integer publishedYear,
                                 List<GenreDto> genres, double averageRating, int ratingCount, int likeCount) {
        public static BookSummaryDto from(Book b) {
            return new BookSummaryDto(b.getId(), b.getTitle(), AuthorRef.from(b.getAuthor()), b.getCoverUrl(),
                    b.getPublishedYear(), genreDtos(b), round2(b.getAvgRating()), b.getRatingCount(), b.getLikeCount());
        }
    }

    /** The caller's own state for a book; null fields for anonymous callers. */
    public record MyBookState(boolean liked, Integer rating, ShelfStatus shelfStatus, Integer progress) {
    }

    public record BookDetailDto(Long id, String title, String description, AuthorRef author, String isbn,
                                String coverUrl, Integer publishedYear, String language, List<GenreDto> genres,
                                double averageRating, int ratingCount, int likeCount, Instant createdAt,
                                Instant updatedAt, MyBookState me) {
        public static BookDetailDto from(Book b, MyBookState me) {
            return new BookDetailDto(b.getId(), b.getTitle(), b.getDescription(), AuthorRef.from(b.getAuthor()),
                    b.getIsbn(), b.getCoverUrl(), b.getPublishedYear(), b.getLanguage(), genreDtos(b),
                    round2(b.getAvgRating()), b.getRatingCount(), b.getLikeCount(), b.getCreatedAt(),
                    b.getUpdatedAt(), me);
        }
    }

    public record BookRequest(
            @NotBlank @Size(max = 255) String title,
            @Size(max = 10000) String description,
            @NotNull Long authorId,
            @Pattern(regexp = "^(?:\\d{9}[\\dXx]|\\d{13}|[\\d-]{10,17}[\\dXx])$", message = "must be a valid ISBN-10 or ISBN-13")
            String isbn,
            @Size(max = 512) @Pattern(regexp = "^https?://\\S+$", message = "must be an http(s) URL") String coverUrl,
            @Min(-3000) @Max(2100) Integer publishedYear,
            @Size(min = 2, max = 32) String language,
            @NotEmpty @Size(max = 10) List<@NotNull Long> genreIds) {
    }

    public record LikeStatusDto(Long bookId, boolean liked, int likeCount) {
    }

    private static List<GenreDto> genreDtos(Book b) {
        return b.getGenres().stream().sorted(Comparator.comparing(g -> g.getName())).map(GenreDto::from).toList();
    }

    static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
