package com.bookflix.common;

import java.time.Instant;
import java.util.List;

/**
 * Standard error payload shared with the frontend. See docs/api/contracts.md.
 */
public record ApiError(String code, String message, List<FieldDetail> details, Instant timestamp, String path) {

    public record FieldDetail(String field, String message) {
    }

    public static ApiError of(ErrorCode code, String message, String path) {
        return new ApiError(code.name(), message, List.of(), Instant.now(), path);
    }

    public static ApiError of(ErrorCode code, String message, List<FieldDetail> details, String path) {
        return new ApiError(code.name(), message, details, Instant.now(), path);
    }
}
