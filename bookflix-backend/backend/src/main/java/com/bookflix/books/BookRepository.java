package com.bookflix.books;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface BookRepository extends JpaRepository<Book, Long> {

    @EntityGraph(attributePaths = {"author", "genres"})
    @Query("SELECT b FROM Book b WHERE b.id = :id AND b.deletedAt IS NULL")
    Optional<Book> findActiveById(Long id);

    /** Loads active books with author + genres in one round trip; caller restores ordering. */
    @EntityGraph(attributePaths = {"author", "genres"})
    @Query("SELECT DISTINCT b FROM Book b WHERE b.id IN :ids AND b.deletedAt IS NULL")
    List<Book> findActiveByIdIn(Collection<Long> ids);

    @EntityGraph(attributePaths = {"author", "genres"})
    @Query("SELECT DISTINCT b FROM Book b WHERE b.deletedAt IS NULL ORDER BY b.id")
    List<Book> findAllActiveWithDetails();

    @Query("SELECT COUNT(b) FROM Book b WHERE b.deletedAt IS NULL")
    long countActive();

    boolean existsByIsbn(String isbn);

    boolean existsByIsbnAndIdNot(String isbn, Long id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE books b SET b.like_count = (SELECT COUNT(*) FROM user_likes l WHERE l.book_id = b.id)
            WHERE b.id = :bookId""", nativeQuery = true)
    void refreshLikeCount(Long bookId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE books b SET
              b.rating_count = (SELECT COUNT(*) FROM reviews r WHERE r.book_id = b.id),
              b.avg_rating = COALESCE((SELECT AVG(r.rating) FROM reviews r WHERE r.book_id = b.id), 0)
            WHERE b.id = :bookId""", nativeQuery = true)
    void refreshRatingStats(Long bookId);

    @Query(value = "SELECT like_count FROM books WHERE id = :bookId", nativeQuery = true)
    int likeCountOf(Long bookId);
}
