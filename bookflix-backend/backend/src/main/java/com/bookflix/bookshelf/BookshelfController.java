package com.bookflix.bookshelf;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.bookflix.bookshelf.BookshelfDtos.BookshelfEntryDto;
import com.bookflix.bookshelf.BookshelfDtos.BookshelfRequest;
import com.bookflix.common.CurrentUser;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/bookshelf")
public class BookshelfController {

    private final BookshelfService bookshelfService;

    public BookshelfController(BookshelfService bookshelfService) {
        this.bookshelfService = bookshelfService;
    }

    /** "Continue Reading" is GET /api/bookshelf?status=READING (a direct shelf query, not ML). */
    @GetMapping
    public List<BookshelfEntryDto> list(@AuthenticationPrincipal Jwt jwt,
                                        @RequestParam(required = false) ShelfStatus status) {
        return bookshelfService.list(CurrentUser.id(jwt), status);
    }

    @PutMapping("/{bookId}")
    public BookshelfEntryDto upsert(@AuthenticationPrincipal Jwt jwt, @PathVariable long bookId,
                                    @Valid @RequestBody BookshelfRequest req) {
        return bookshelfService.upsert(CurrentUser.id(jwt), bookId, req);
    }

    @DeleteMapping("/{bookId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@AuthenticationPrincipal Jwt jwt, @PathVariable long bookId) {
        bookshelfService.remove(CurrentUser.id(jwt), bookId);
    }
}
