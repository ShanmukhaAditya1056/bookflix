package com.bookflix.recommendations;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.bookflix.recommendations.MlDtos.RatingSignal;
import com.bookflix.recommendations.MlDtos.ShelfSignal;

/**
 * SQL-side ranking used for Trending (canonical owner), "Based on your genres", and as the fallback
 * when the ML service is unavailable. All queries are indexed and LIMITed.
 */
@Repository
public class RecommendationQueries {

    static final Duration TRENDING_WINDOW = Duration.ofDays(30);
    private static final List<Long> NONE = List.of(-1L); // keeps "NOT IN (:ids)" valid when empty

    private final NamedParameterJdbcTemplate jdbc;

    public RecommendationQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Ranked(long bookId, double score) {
    }

    @Transactional(readOnly = true)
    public UserSignals loadSignals(long userId) {
        MapSqlParameterSource p = new MapSqlParameterSource("userId", userId);
        List<Long> genres = jdbc.queryForList(
                "SELECT genre_id FROM user_genres WHERE user_id = :userId ORDER BY genre_id", p, Long.class);
        List<Long> likes = jdbc.queryForList("""
                SELECT l.book_id FROM user_likes l JOIN books b ON b.id = l.book_id
                WHERE l.user_id = :userId AND b.deleted_at IS NULL ORDER BY l.created_at DESC, l.book_id""", p, Long.class);
        List<RatingSignal> ratings = jdbc.query("""
                SELECT r.book_id, r.rating FROM reviews r JOIN books b ON b.id = r.book_id
                WHERE r.user_id = :userId AND b.deleted_at IS NULL ORDER BY r.updated_at DESC, r.book_id""", p,
                (rs, i) -> new RatingSignal(rs.getLong(1), rs.getInt(2)));
        List<ShelfSignal> shelf = jdbc.query("""
                SELECT s.book_id, s.status FROM bookshelf s JOIN books b ON b.id = s.book_id
                WHERE s.user_id = :userId AND b.deleted_at IS NULL ORDER BY s.updated_at DESC, s.book_id""", p,
                (rs, i) -> new ShelfSignal(rs.getLong(1), rs.getString(2)));
        return new UserSignals(genres, likes, ratings, shelf);
    }

    /** Most recent positive anchor for "Because you liked X": latest like, else latest rating >= 4. */
    public Long latestPositiveAnchor(UserSignals signals) {
        Set<Long> disliked = signals.dislikedBookIds();
        for (Long id : signals.likedBookIds()) {
            if (!disliked.contains(id)) {
                return id;
            }
        }
        return signals.ratings().stream().filter(r -> r.rating() >= 4).map(RatingSignal::bookId).findFirst()
                .orElse(null);
    }

    public List<Long> genresOfBooks(Collection<Long> bookIds) {
        if (bookIds.isEmpty()) {
            return List.of();
        }
        return jdbc.queryForList("SELECT DISTINCT genre_id FROM book_genres WHERE book_id IN (:ids)",
                new MapSqlParameterSource("ids", bookIds), Long.class);
    }

    /** Active books sharing the most genres with {@code genreIds}, then by popularity. */
    public List<Ranked> byGenreOverlap(Collection<Long> genreIds, Collection<Long> exclude, int limit) {
        if (genreIds.isEmpty()) {
            return List.of();
        }
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("genres", genreIds)
                .addValue("exclude", exclude.isEmpty() ? NONE : exclude)
                .addValue("limit", limit);
        List<Object[]> rows = jdbc.query("""
                SELECT b.id, COUNT(*) AS overlap, b.like_count + b.rating_count AS engagement
                FROM books b JOIN book_genres bg ON bg.book_id = b.id AND bg.genre_id IN (:genres)
                WHERE b.deleted_at IS NULL AND b.id NOT IN (:exclude)
                GROUP BY b.id, b.like_count, b.rating_count, b.avg_rating
                ORDER BY overlap DESC, b.like_count DESC, b.rating_count DESC, b.avg_rating DESC, b.id ASC
                LIMIT :limit""", p, (rs, i) -> new Object[]{rs.getLong(1), rs.getInt(2), rs.getInt(3)});
        int maxOverlap = rows.stream().mapToInt(r -> (int) r[1]).max().orElse(1);
        int maxEng = Math.max(1, rows.stream().mapToInt(r -> (int) r[2]).max().orElse(1));
        List<Ranked> out = new ArrayList<>();
        for (Object[] r : rows) {
            double score = 0.75 * ((int) r[1] / (double) maxOverlap) + 0.25 * ((int) r[2] / (double) maxEng);
            out.add(new Ranked((long) r[0], round(score)));
        }
        return out;
    }

    /** Books similar to {@code sourceId} by shared genres and same author (fallback for /similar). */
    public List<Ranked> similarByMetadata(long sourceId, Collection<Long> exclude, int limit) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("src", sourceId)
                .addValue("exclude", exclude.isEmpty() ? NONE : exclude)
                .addValue("limit", limit);
        List<Object[]> rows = jdbc.query("""
                SELECT b.id,
                       (SELECT COUNT(*) FROM book_genres x JOIN book_genres y ON y.genre_id = x.genre_id
                        WHERE x.book_id = :src AND y.book_id = b.id) AS overlap,
                       CASE WHEN b.author_id = (SELECT author_id FROM books WHERE id = :src) THEN 1 ELSE 0 END AS same_author
                FROM books b
                WHERE b.deleted_at IS NULL AND b.id <> :src AND b.id NOT IN (:exclude)
                HAVING overlap > 0 OR same_author = 1
                ORDER BY overlap DESC, same_author DESC, b.like_count DESC, b.rating_count DESC, b.id ASC
                LIMIT :limit""", p, (rs, i) -> new Object[]{rs.getLong(1), rs.getInt(2), rs.getInt(3)});
        int maxOverlap = Math.max(1, rows.stream().mapToInt(r -> (int) r[1]).max().orElse(1));
        return rows.stream()
                .map(r -> new Ranked((long) r[0], round(0.8 * ((int) r[1] / (double) maxOverlap) + 0.2 * (int) r[2])))
                .toList();
    }

    /** Canonical Trending: recent likes (x3), positive reviews (x2), shelf activity (x1); ties by all-time popularity. */
    public List<Ranked> trending(Collection<Long> exclude, int limit) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("since", java.sql.Timestamp.from(Instant.now().minus(TRENDING_WINDOW)))
                .addValue("exclude", exclude.isEmpty() ? NONE : exclude)
                .addValue("limit", limit);
        List<Object[]> rows = jdbc.query("""
                SELECT b.id,
                       3 * COALESCE(l.cnt, 0) + 2 * COALESCE(r.cnt, 0) + COALESCE(s.cnt, 0) AS recent,
                       b.like_count + b.rating_count AS engagement
                FROM books b
                LEFT JOIN (SELECT book_id, COUNT(*) cnt FROM user_likes WHERE created_at >= :since GROUP BY book_id) l
                       ON l.book_id = b.id
                LEFT JOIN (SELECT book_id, COUNT(*) cnt FROM reviews WHERE updated_at >= :since AND rating >= 4
                           GROUP BY book_id) r ON r.book_id = b.id
                LEFT JOIN (SELECT book_id, COUNT(*) cnt FROM bookshelf WHERE updated_at >= :since GROUP BY book_id) s
                       ON s.book_id = b.id
                WHERE b.deleted_at IS NULL AND b.id NOT IN (:exclude)
                ORDER BY recent DESC, b.like_count DESC, b.rating_count DESC, b.avg_rating DESC, b.id ASC
                LIMIT :limit""", p, (rs, i) -> new Object[]{rs.getLong(1), rs.getInt(2), rs.getInt(3)});
        int maxRecent = Math.max(1, rows.stream().mapToInt(r -> (int) r[1]).max().orElse(1));
        int maxEng = Math.max(1, rows.stream().mapToInt(r -> (int) r[2]).max().orElse(1));
        return rows.stream()
                .map(r -> new Ranked((long) r[0],
                        round(0.8 * ((int) r[1] / (double) maxRecent) + 0.2 * ((int) r[2] / (double) maxEng))))
                .toList();
    }

    public List<Ranked> popular(Collection<Long> exclude, int limit) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("exclude", exclude.isEmpty() ? NONE : exclude)
                .addValue("limit", limit);
        List<Object[]> rows = jdbc.query("""
                SELECT b.id, b.like_count + b.rating_count FROM books b
                WHERE b.deleted_at IS NULL AND b.id NOT IN (:exclude)
                ORDER BY b.like_count DESC, b.rating_count DESC, b.avg_rating DESC, b.id ASC
                LIMIT :limit""", p, (rs, i) -> new Object[]{rs.getLong(1), rs.getInt(2)});
        int maxEng = Math.max(1, rows.stream().mapToInt(r -> (int) r[1]).max().orElse(1));
        return rows.stream().map(r -> new Ranked((long) r[0], round((int) r[1] / (double) maxEng))).toList();
    }

    static double round(double v) {
        if (!Double.isFinite(v)) {
            return 0.0;
        }
        return Math.round(v * 1_000_000.0) / 1_000_000.0;
    }
}
