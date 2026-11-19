package com.bookflix.recommendations;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bookflix.books.Book;
import com.bookflix.books.BookRepository;
import com.bookflix.recommendations.MlDtos.CatalogBook;
import com.bookflix.recommendations.MlDtos.GenreRef;
import com.bookflix.recommendations.MlDtos.InteractionSet;
import com.bookflix.recommendations.MlDtos.RebuildRequest;

/** Builds the catalog + anonymised interaction snapshot that the ML service trains on. */
@Service
public class CatalogSnapshotService {

    private final BookRepository books;
    private final JdbcClient jdbc;

    public CatalogSnapshotService(BookRepository books, JdbcClient jdbc) {
        this.books = books;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public RebuildRequest snapshot() {
        Map<Long, Integer> shelfCounts = new HashMap<>();
        jdbc.sql("SELECT book_id, COUNT(*) FROM bookshelf GROUP BY book_id")
                .query((rs, i) -> Map.entry(rs.getLong(1), rs.getInt(2))).list()
                .forEach(e -> shelfCounts.put(e.getKey(), e.getValue()));

        List<CatalogBook> catalog = new ArrayList<>();
        for (Book b : books.findAllActiveWithDetails()) {
            List<GenreRef> genres = b.getGenres().stream()
                    .sorted(Comparator.comparing(g -> g.getId()))
                    .map(g -> new GenreRef(g.getId(), g.getName())).toList();
            catalog.add(new CatalogBook(b.getId(), b.getTitle(), b.getDescription(), b.getAuthor().getName(), genres,
                    b.getLanguage(), b.getPublishedYear(), b.getLikeCount(), b.getRatingCount(), b.getAvgRating(),
                    shelfCounts.getOrDefault(b.getId(), 0)));
        }
        return new RebuildRequest(fingerprint(catalog), catalog, interactions());
    }

    /** Positive interactions grouped per user; user ids are replaced by a sequential index. */
    private List<InteractionSet> interactions() {
        Map<Long, Set<Long>> byUser = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT x.user_id, x.book_id FROM (
                    SELECT user_id, book_id FROM user_likes
                    UNION SELECT user_id, book_id FROM reviews WHERE rating >= 4
                    UNION SELECT user_id, book_id FROM bookshelf WHERE status IN ('READING', 'COMPLETED')
                ) x
                JOIN books b ON b.id = x.book_id AND b.deleted_at IS NULL
                WHERE NOT EXISTS (SELECT 1 FROM reviews r WHERE r.user_id = x.user_id AND r.book_id = x.book_id
                                  AND r.rating <= 2)
                ORDER BY x.user_id, x.book_id""")
                .query((rs, i) -> Map.entry(rs.getLong(1), rs.getLong(2))).list()
                .forEach(e -> byUser.computeIfAbsent(e.getKey(), k -> new LinkedHashSet<>()).add(e.getValue()));
        List<InteractionSet> out = new ArrayList<>();
        int idx = 0;
        for (Set<Long> positives : byUser.values()) {
            out.add(new InteractionSet(idx++, List.copyOf(positives)));
        }
        return out;
    }

    /** Fingerprint over the text features only (not popularity), so it changes iff the TF-IDF input changes. */
    static String fingerprint(List<CatalogBook> catalog) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (CatalogBook b : catalog) {
                md.update((b.id() + "\u0001" + b.title() + "\u0001" + b.description() + "\u0001" + b.author()
                        + "\u0001" + b.genres() + "\u0002").getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
