package com.bookflix.books;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.bookflix.books.BookDtos.BookDetailDto;
import com.bookflix.books.BookDtos.BookSummaryDto;
import com.bookflix.books.BookDtos.LikeStatusDto;
import com.bookflix.common.CurrentUser;
import com.bookflix.common.PageResponse;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/books")
@Validated
public class BookController {

    private final BookService bookService;
    private final LikeService likeService;

    public BookController(BookService bookService, LikeService likeService) {
        this.bookService = bookService;
        this.likeService = likeService;
    }

    @GetMapping
    public PageResponse<BookSummaryDto> list(
            @RequestParam(defaultValue = "0") @Min(0) @Max(10_000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) Long genreId,
            @RequestParam(required = false) String sort) {
        return bookService.search(q, genreId, sort, page, size);
    }

    @GetMapping("/{id}")
    public BookDetailDto get(@PathVariable long id) {
        return bookService.detail(id, CurrentUser.idOrNull());
    }

    @PostMapping("/{id}/like")
    public LikeStatusDto like(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return likeService.like(CurrentUser.id(jwt), id);
    }

    @DeleteMapping("/{id}/like")
    public LikeStatusDto unlike(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return likeService.unlike(CurrentUser.id(jwt), id);
    }
}
