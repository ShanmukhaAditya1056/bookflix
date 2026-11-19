package com.bookflix.users;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bookflix.bookshelf.ShelfStatus;
import com.bookflix.common.ApiException;
import com.bookflix.common.ErrorCode;
import com.bookflix.genres.Genre;
import com.bookflix.genres.GenreRepository;
import com.bookflix.users.UserDtos.UpdateGenresRequest;
import com.bookflix.users.UserDtos.UpdateProfileRequest;
import com.bookflix.users.UserDtos.UserDto;
import com.bookflix.users.UserDtos.UserStatsDto;

@Service
public class UserService {

    private final UserRepository users;
    private final GenreRepository genres;
    private final JdbcClient jdbc;

    public UserService(UserRepository users, GenreRepository genres, JdbcClient jdbc) {
        this.users = users;
        this.genres = genres;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public UserDto me(long userId) {
        return UserDto.from(load(userId));
    }

    @Transactional
    public UserDto updateProfile(long userId, UpdateProfileRequest req) {
        User user = load(userId);
        user.setName(req.name().trim());
        return UserDto.from(user);
    }

    @Transactional
    public UserDto updateGenres(long userId, UpdateGenresRequest req) {
        User user = load(userId);
        List<Long> ids = req.genreIds().stream().distinct().toList();
        List<Genre> found = genres.findAllById(ids);
        if (found.size() != ids.size()) {
            throw ApiException.badRequest("One or more genreIds do not exist");
        }
        user.getGenres().clear();
        user.getGenres().addAll(new HashSet<>(found));
        user.setOnboardingCompleted(true);
        return UserDto.from(user);
    }

    @Transactional(readOnly = true)
    public UserStatsDto stats(long userId) {
        load(userId);
        long liked = jdbc.sql("""
                SELECT COUNT(*) FROM user_likes l JOIN books b ON b.id = l.book_id
                WHERE l.user_id = ? AND b.deleted_at IS NULL""").param(userId).query(Long.class).single();
        Map<String, Object> reviewAgg = jdbc.sql("""
                SELECT COUNT(*) AS cnt, AVG(r.rating) AS avg FROM reviews r JOIN books b ON b.id = r.book_id
                WHERE r.user_id = ? AND b.deleted_at IS NULL""").param(userId).query().singleRow();
        Map<String, Long> shelf = new LinkedHashMap<>();
        for (ShelfStatus s : ShelfStatus.values()) {
            shelf.put(s.name(), 0L);
        }
        jdbc.sql("""
                SELECT s.status, COUNT(*) FROM bookshelf s JOIN books b ON b.id = s.book_id
                WHERE s.user_id = ? AND b.deleted_at IS NULL GROUP BY s.status""")
                .param(userId)
                .query((rs, i) -> Map.entry(rs.getString(1), rs.getLong(2)))
                .list()
                .forEach(e -> shelf.put(e.getKey(), e.getValue()));
        Number avg = (Number) reviewAgg.get("avg");
        Double avgRating = avg == null ? null : Math.round(avg.doubleValue() * 100.0) / 100.0;
        return new UserStatsDto(liked, ((Number) reviewAgg.get("cnt")).longValue(), avgRating, shelf);
    }

    private User load(long userId) {
        return users.findWithGenresById(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED, "User no longer exists"));
    }
}
