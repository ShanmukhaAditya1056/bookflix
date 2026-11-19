package com.bookflix.books;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface UserLikeRepository extends JpaRepository<UserLike, UserLike.Key> {

    /** Idempotent insert; avoids a race on the composite primary key. */
    @Modifying
    @Query(value = "INSERT IGNORE INTO user_likes (user_id, book_id, created_at) VALUES (:userId, :bookId, UTC_TIMESTAMP(6))",
            nativeQuery = true)
    int insertIgnore(Long userId, Long bookId);

    @Modifying
    @Query(value = "DELETE FROM user_likes WHERE user_id = :userId AND book_id = :bookId", nativeQuery = true)
    int deleteByUserAndBook(Long userId, Long bookId);

    @Query(value = "SELECT book_id FROM user_likes WHERE user_id = :userId ORDER BY created_at DESC", nativeQuery = true)
    List<Long> findBookIdsByUserNewestFirst(Long userId);

    @Query(value = "SELECT COUNT(*) FROM user_likes WHERE user_id = :userId AND book_id = :bookId", nativeQuery = true)
    long countByUserAndBook(Long userId, Long bookId);
}
