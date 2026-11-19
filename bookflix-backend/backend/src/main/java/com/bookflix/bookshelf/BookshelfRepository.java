package com.bookflix.bookshelf;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface BookshelfRepository extends JpaRepository<BookshelfEntry, Long> {

    Optional<BookshelfEntry> findByUserIdAndBookId(Long userId, Long bookId);

    /** Only entries whose book is still active (soft-deleted books are hidden). */
    @Query("""
            SELECT s FROM BookshelfEntry s WHERE s.userId = :userId
              AND (:status IS NULL OR s.status = :status)
              AND EXISTS (SELECT 1 FROM Book b WHERE b.id = s.bookId AND b.deletedAt IS NULL)
            ORDER BY s.updatedAt DESC, s.id DESC""")
    List<BookshelfEntry> findActiveForUser(Long userId, ShelfStatus status);

    List<BookshelfEntry> findAllByUserId(Long userId);

    @Modifying
    @Query("DELETE FROM BookshelfEntry s WHERE s.userId = :userId AND s.bookId = :bookId")
    int deleteByUserAndBook(Long userId, Long bookId);
}
