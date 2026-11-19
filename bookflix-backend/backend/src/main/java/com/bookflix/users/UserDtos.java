package com.bookflix.users;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.bookflix.genres.GenreDtos.GenreDto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class UserDtos {

    private UserDtos() {
    }

    public record UserDto(Long id, String name, String email, Role role, boolean onboardingCompleted,
                          List<GenreDto> genres, Instant createdAt) {
        public static UserDto from(User u) {
            List<GenreDto> genres = u.getGenres().stream()
                    .sorted(Comparator.comparing(g -> g.getName()))
                    .map(GenreDto::from).toList();
            return new UserDto(u.getId(), u.getName(), u.getEmail(), u.getRole(), u.isOnboardingCompleted(),
                    genres, u.getCreatedAt());
        }
    }

    public record UpdateProfileRequest(@NotBlank @Size(min = 1, max = 100) String name) {
    }

    /** Empty list = "skip onboarding": marks onboarding complete with no preferred genres. */
    public record UpdateGenresRequest(@NotNull @Size(max = 20) List<@NotNull Long> genreIds) {
    }

    public record UserStatsDto(long likedCount, long reviewCount, Double averageRatingGiven,
                               Map<String, Long> bookshelf) {
    }
}
