package com.bookflix.authors;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface AuthorRepository extends JpaRepository<Author, Long> {

    List<Author> findAllByOrderByNameAsc();

    List<Author> findByNameContainingIgnoreCaseOrderByNameAsc(String q);

    @Query(value = "SELECT COUNT(*) FROM books WHERE author_id = :authorId", nativeQuery = true)
    long countBooksByAuthor(Long authorId);

    /** Keeps books.search_text in sync after an author rename. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE books b JOIN authors a ON a.id = b.author_id
               SET b.search_text = CONCAT_WS(' ', b.title, a.name, b.description)
             WHERE b.author_id = :authorId""", nativeQuery = true)
    int refreshBookSearchText(Long authorId);
}
