package com.bookflix.authors;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AuthorDtos {

    private AuthorDtos() {
    }

    public record AuthorDto(Long id, String name, String bio, Instant createdAt) {
        public static AuthorDto from(Author a) {
            return new AuthorDto(a.getId(), a.getName(), a.getBio(), a.getCreatedAt());
        }
    }

    /** Compact author reference embedded in book payloads. */
    public record AuthorRef(Long id, String name) {
        public static AuthorRef from(Author a) {
            return new AuthorRef(a.getId(), a.getName());
        }
    }

    public record AuthorRequest(@NotBlank @Size(max = 200) String name, @Size(max = 5000) String bio) {
    }
}
