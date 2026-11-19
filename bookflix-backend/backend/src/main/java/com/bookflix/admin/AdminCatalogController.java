package com.bookflix.admin;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.bookflix.authors.AuthorDtos.AuthorDto;
import com.bookflix.authors.AuthorDtos.AuthorRequest;
import com.bookflix.authors.AuthorService;
import com.bookflix.books.BookDtos.BookDetailDto;
import com.bookflix.books.BookDtos.BookRequest;
import com.bookflix.books.BookService;
import com.bookflix.genres.GenreDtos.GenreDto;
import com.bookflix.genres.GenreDtos.GenreRequest;
import com.bookflix.genres.GenreService;

import jakarta.validation.Valid;

/**
 * Admin catalog CRUD. Access is enforced by SecurityConfig (/api/admin/** requires ROLE_ADMIN).
 * Reads reuse the public endpoints (GET /api/books, /api/authors, /api/genres).
 */
@RestController
@RequestMapping("/api/admin")
public class AdminCatalogController {

    private final BookService books;
    private final AuthorService authors;
    private final GenreService genres;

    public AdminCatalogController(BookService books, AuthorService authors, GenreService genres) {
        this.books = books;
        this.authors = authors;
        this.genres = genres;
    }

    @PostMapping("/books")
    @ResponseStatus(HttpStatus.CREATED)
    public BookDetailDto createBook(@Valid @RequestBody BookRequest req) {
        return books.create(req);
    }

    @PutMapping("/books/{id}")
    public BookDetailDto updateBook(@PathVariable long id, @Valid @RequestBody BookRequest req) {
        return books.update(id, req);
    }

    @DeleteMapping("/books/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteBook(@PathVariable long id) {
        books.delete(id);
    }

    @PostMapping("/authors")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthorDto createAuthor(@Valid @RequestBody AuthorRequest req) {
        return authors.create(req);
    }

    @PutMapping("/authors/{id}")
    public AuthorDto updateAuthor(@PathVariable long id, @Valid @RequestBody AuthorRequest req) {
        return authors.update(id, req);
    }

    @DeleteMapping("/authors/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAuthor(@PathVariable long id) {
        authors.delete(id);
    }

    @PostMapping("/genres")
    @ResponseStatus(HttpStatus.CREATED)
    public GenreDto createGenre(@Valid @RequestBody GenreRequest req) {
        return genres.create(req);
    }

    @PutMapping("/genres/{id}")
    public GenreDto updateGenre(@PathVariable long id, @Valid @RequestBody GenreRequest req) {
        return genres.update(id, req);
    }

    @DeleteMapping("/genres/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteGenre(@PathVariable long id) {
        genres.delete(id);
    }
}
