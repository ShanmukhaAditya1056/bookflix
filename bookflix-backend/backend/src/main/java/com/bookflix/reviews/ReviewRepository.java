package com.bookflix.reviews;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ReviewRepository extends JpaRepository<Review, Long> {

    @EntityGraph(attributePaths = "user")
    Page<Review> findByBookId(Long bookId, Pageable pageable);

    @Query("SELECT r FROM Review r WHERE r.user.id = :userId AND r.bookId = :bookId")
    Optional<Review> findByUserAndBook(Long userId, Long bookId);

    @Query("SELECT r FROM Review r WHERE r.user.id = :userId")
    List<Review> findAllByUser(Long userId);
}
