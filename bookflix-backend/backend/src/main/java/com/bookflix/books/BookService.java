package com.bookflix.books;

import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bookflix.authors.Author;
import com.bookflix.authors.AuthorRepository;
import com.bookflix.books.BookDtos.BookDetailDto;
import com.bookflix.books.BookDtos.BookRequest;
import com.bookflix.books.BookDtos.BookSummaryDto;
import com.bookflix.books.BookDtos.MyBookState;
import com.bookflix.bookshelf.BookshelfEntry;
import com.bookflix.bookshelf.BookshelfRepository;
import com.bookflix.common.ApiException;
import com.bookflix.common.PageResponse;
import com.bookflix.genres.Genre;
import com.bookflix.genres.GenreRepository;
import com.bookflix.recommendations.ModelRebuildService;
import com.bookflix.reviews.Review;
import com.bookflix.reviews.ReviewRepository;

@Service
public class BookService {

    private final BookRepository books;
    private final BookSearchRepository search;
    private final AuthorRepository authors;
    private final GenreRepository genres;
    private final UserLikeRepository likes;
    private final ReviewRepository reviews;
    private final BookshelfRepository shelf;
    private final ModelRebuildService modelRebuild;

    public BookService(BookRepository books, BookSearchRepository search, AuthorRepository authors,
                       GenreRepository genres, UserLikeRepository likes, ReviewRepository reviews,
                       BookshelfRepository shelf, ModelRebuildService modelRebuild) {
        this.books = books;
        this.search = search;
        this.authors = authors;
        this.genres = genres;
        this.likes = likes;
        this.reviews = reviews;
        this.shelf = shelf;
        this.modelRebuild = modelRebuild;
    }

    @Transactional(readOnly = true)
    public PageResponse<BookSummaryDto> search(String q, Long genreId, String sort, int page, int size) {
        BookSearchRepository.IdPage ids = search.search(q, genreId, BookSort.parse(sort), page, size);
        return PageResponse.of(summariesInOrder(ids.ids()), page, size, ids.total());
    }

    @Transactional(readOnly = true)
    public BookDetailDto detail(long id, Long userIdOrNull) {
        Book book = requireActive(id);
        MyBookState me = null;
        if (userIdOrNull != null) {
            boolean liked = likes.countByUserAndBook(userIdOrNull, id) > 0;
            Integer rating = reviews.findByUserAndBook(userIdOrNull, id).map(Review::getRating).orElse(null);
            BookshelfEntry entry = shelf.findByUserIdAndBookId(userIdOrNull, id).orElse(null);
            me = new MyBookState(liked, rating, entry == null ? null : entry.getStatus(),
                    entry == null ? null : entry.getProgress());
        }
        return BookDetailDto.from(book, me);
    }

    /** Loads active books by id preserving the given order; silently drops unknown/deleted ids. */
    @Transactional(readOnly = true)
    public List<BookSummaryDto> summariesInOrder(List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, Book> byId = books.findActiveByIdIn(new HashSet<>(ids)).stream()
                .collect(Collectors.toMap(Book::getId, Function.identity()));
        return ids.stream().distinct().map(byId::get).filter(Objects::nonNull).map(BookSummaryDto::from).toList();
    }

    @Transactional(readOnly = true)
    public Map<Long, BookSummaryDto> summariesById(List<Long> ids) {
        Map<Long, BookSummaryDto> out = new LinkedHashMap<>();
        summariesInOrder(ids).forEach(s -> out.put(s.id(), s));
        return out;
    }

    public Book requireActive(long id) {
        return books.findActiveById(id).orElseThrow(() -> ApiException.notFound("Book"));
    }

    // ---- admin ----

    @Transactional
    public BookDetailDto create(BookRequest req) {
        String isbn = normaliseIsbn(req.isbn());
        if (isbn != null && books.existsByIsbn(isbn)) {
            throw ApiException.conflict("A book with this ISBN already exists");
        }
        Book book = new Book(req.title().trim(), requireAuthor(req.authorId()));
        apply(book, req, isbn);
        books.saveAndFlush(book);
        modelRebuild.markCatalogChanged();
        return BookDetailDto.from(book, null);
    }

    @Transactional
    public BookDetailDto update(long id, BookRequest req) {
        Book book = requireActive(id);
        String isbn = normaliseIsbn(req.isbn());
        if (isbn != null && books.existsByIsbnAndIdNot(isbn, id)) {
            throw ApiException.conflict("A book with this ISBN already exists");
        }
        book.setTitle(req.title().trim());
        book.setAuthor(requireAuthor(req.authorId()));
        apply(book, req, isbn);
        books.saveAndFlush(book);
        modelRebuild.markCatalogChanged();
        return BookDetailDto.from(book, null);
    }

    /**
     * Deletion policy: books are always soft-deleted. They disappear from catalog, search, shelves and
     * recommendations, but reviews/likes/shelf rows are retained for audit and model history.
     */
    @Transactional
    public void delete(long id) {
        Book book = requireActive(id);
        book.setDeletedAt(Instant.now());
        modelRebuild.markCatalogChanged();
    }

    private void apply(Book book, BookRequest req, String isbn) {
        book.setDescription(req.description() == null ? null : req.description().trim());
        book.setIsbn(isbn);
        book.setCoverUrl(req.coverUrl() == null || req.coverUrl().isBlank() ? null : req.coverUrl().trim());
        book.setPublishedYear(req.publishedYear());
        book.setLanguage(req.language() == null || req.language().isBlank() ? "en" : req.language().trim());
        List<Long> genreIds = req.genreIds().stream().distinct().toList();
        List<Genre> found = genres.findAllById(genreIds);
        if (found.size() != genreIds.size()) {
            throw ApiException.badRequest("One or more genreIds do not exist");
        }
        book.getGenres().clear();
        book.getGenres().addAll(found);
        book.refreshSearchText();
    }

    private Author requireAuthor(Long authorId) {
        return authors.findById(authorId).orElseThrow(() -> ApiException.badRequest("authorId does not exist"));
    }

    static String normaliseIsbn(String isbn) {
        if (isbn == null || isbn.isBlank()) {
            return null;
        }
        String compact = isbn.replace("-", "").toUpperCase();
        if (!(compact.matches("\\d{9}[\\dX]") || compact.matches("\\d{13}"))) {
            throw ApiException.badRequest("isbn must be a valid ISBN-10 or ISBN-13");
        }
        return compact;
    }
}
