package com.bookflix.books;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Catalog search over indexed columns. Text search uses the InnoDB FULLTEXT index on
 * books.search_text (title + author name + description) in BOOLEAN MODE with prefix matching, so
 * the catalog is never loaded into Java memory. Every search token must match.
 */
@Repository
public class BookSearchRepository {

    /** InnoDB's default full-text stopword list: such tokens never match, so they are dropped. */
    private static final Set<String> INNODB_STOPWORDS = Set.of("a", "about", "an", "are", "as", "at", "be", "by",
            "com", "de", "en", "for", "from", "how", "i", "in", "is", "it", "la", "of", "on", "or", "that", "the",
            "this", "to", "was", "what", "when", "where", "who", "will", "with", "und", "www");
    private static final int MIN_TOKEN = 3; // innodb_ft_min_token_size default
    private static final int MAX_TOKENS = 8;

    private final NamedParameterJdbcTemplate jdbc;

    public BookSearchRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record IdPage(List<Long> ids, long total) {
    }

    public IdPage search(String q, Long genreId, BookSort sort, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE b.deleted_at IS NULL");
        MapSqlParameterSource params = new MapSqlParameterSource();

        if (genreId != null) {
            where.append(" AND EXISTS (SELECT 1 FROM book_genres bg WHERE bg.book_id = b.id AND bg.genre_id = :genreId)");
            params.addValue("genreId", genreId);
        }

        if (q != null && !q.isBlank()) {
            List<String> tokens = tokenize(q);
            if (tokens.isEmpty()) {
                // Only very short / stopword input: fall back to an index-friendly title prefix match.
                // (backslash is MySQL's default LIKE escape character)
                where.append(" AND b.title LIKE :titlePrefix");
                params.addValue("titlePrefix", escapeLike(q.trim()) + "%");
            } else {
                // Every token required, prefix-matched: "+war* +tolstoy*"
                where.append(" AND MATCH(b.search_text) AGAINST (:ft IN BOOLEAN MODE)");
                params.addValue("ft", String.join(" ", tokens.stream().map(t -> "+" + t + "*").toList()));
            }
        }

        String from = " FROM books b";
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + from + where, params, Long.class);
        if (total == null || total == 0) {
            return new IdPage(List.of(), 0);
        }
        params.addValue("limit", size).addValue("offset", (long) page * size);
        List<Long> ids = jdbc.queryForList("SELECT b.id" + from + where + " ORDER BY " + sort.orderBy()
                + " LIMIT :limit OFFSET :offset", params, Long.class);
        return new IdPage(ids, total);
    }

    static List<String> tokenize(String q) {
        List<String> out = new ArrayList<>();
        for (String raw : q.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (raw.length() >= MIN_TOKEN && !INNODB_STOPWORDS.contains(raw) && !out.contains(raw)) {
                out.add(raw);
                if (out.size() == MAX_TOKENS) {
                    break;
                }
            }
        }
        return out;
    }

    static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
