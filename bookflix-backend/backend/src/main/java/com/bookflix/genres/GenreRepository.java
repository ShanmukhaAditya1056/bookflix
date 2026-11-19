package com.bookflix.genres;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface GenreRepository extends JpaRepository<Genre, Long> {

    List<Genre> findAllByOrderByNameAsc();

    boolean existsByNameIgnoreCase(String name);

    boolean existsBySlug(String slug);

    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);

    boolean existsBySlugAndIdNot(String slug, Long id);

    @Query(value = """
            SELECT COUNT(*) FROM book_genres bg JOIN books b ON b.id = bg.book_id
            WHERE bg.genre_id = :genreId AND b.deleted_at IS NULL""", nativeQuery = true)
    long countActiveBooksWithGenre(Long genreId);
}
