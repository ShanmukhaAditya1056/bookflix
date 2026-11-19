package com.bookflix.books;

import java.util.Arrays;
import java.util.Locale;

import com.bookflix.common.ApiException;

/** Whitelisted sort keys for GET /api/books. Maps to fixed SQL; never interpolates user input. */
public enum BookSort {
    TITLE("b.title ASC, b.id ASC"),
    NEWEST("b.created_at DESC, b.id DESC"),
    YEAR_DESC("b.published_year IS NULL, b.published_year DESC, b.id ASC"),
    YEAR_ASC("b.published_year IS NULL, b.published_year ASC, b.id ASC"),
    RATING("b.avg_rating DESC, b.rating_count DESC, b.id ASC"),
    POPULAR("b.like_count DESC, b.rating_count DESC, b.id ASC");

    private final String orderBy;

    BookSort(String orderBy) {
        this.orderBy = orderBy;
    }

    public String orderBy() {
        return orderBy;
    }

    public static BookSort parse(String value) {
        if (value == null || value.isBlank()) {
            return TITLE;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Unsupported sort '" + value + "'. Allowed: "
                    + Arrays.stream(values()).map(s -> s.name().toLowerCase(Locale.ROOT)).toList());
        }
    }
}
