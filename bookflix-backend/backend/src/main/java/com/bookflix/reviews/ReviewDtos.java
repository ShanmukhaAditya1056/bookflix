package com.bookflix.reviews;

import java.time.Instant;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class ReviewDtos {

    private ReviewDtos() {
    }

    public record ReviewerRef(Long id, String name) {
    }

    public record ReviewDto(Long id, Long bookId, ReviewerRef user, int rating, String comment, Instant createdAt,
                            Instant updatedAt) {
        public static ReviewDto from(Review r) {
            return new ReviewDto(r.getId(), r.getBookId(), new ReviewerRef(r.getUser().getId(), r.getUser().getName()),
                    r.getRating(), r.getComment(), r.getCreatedAt(), r.getUpdatedAt());
        }
    }

    public record ReviewRequest(@NotNull @Min(1) @Max(5) Integer rating, @Size(max = 2000) String comment) {
    }
}
