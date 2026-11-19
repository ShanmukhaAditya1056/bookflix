package com.bookflix.genres;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class GenreDtos {

    private GenreDtos() {
    }

    public record GenreDto(Long id, String name, String slug) {
        public static GenreDto from(Genre g) {
            return new GenreDto(g.getId(), g.getName(), g.getSlug());
        }
    }

    /** slug is optional; derived from name when omitted. */
    public record GenreRequest(
            @NotBlank @Size(max = 80) String name,
            @Size(max = 80) @Pattern(regexp = "^[a-z0-9]+(?:-[a-z0-9]+)*$", message = "must be lowercase kebab-case")
            String slug) {
    }
}
