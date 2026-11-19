package com.bookflix.bookshelf;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bookflix.books.BookDtos.BookSummaryDto;
import com.bookflix.books.BookService;
import com.bookflix.bookshelf.BookshelfDtos.BookshelfEntryDto;
import com.bookflix.bookshelf.BookshelfDtos.BookshelfRequest;

@Service
public class BookshelfService {

    private final BookshelfRepository shelf;
    private final BookService books;

    public BookshelfService(BookshelfRepository shelf, BookService books) {
        this.shelf = shelf;
        this.books = books;
    }

    @Transactional(readOnly = true)
    public List<BookshelfEntryDto> list(long userId, ShelfStatus status) {
        List<BookshelfEntry> entries = shelf.findActiveForUser(userId, status);
        Map<Long, BookSummaryDto> summaries = books.summariesById(entries.stream().map(BookshelfEntry::getBookId).toList());
        return entries.stream()
                .filter(e -> summaries.containsKey(e.getBookId()))
                .map(e -> toDto(e, summaries.get(e.getBookId())))
                .toList();
    }

    @Transactional
    public BookshelfEntryDto upsert(long userId, long bookId, BookshelfRequest req) {
        BookSummaryDto summary = BookSummaryDto.from(books.requireActive(bookId));
        BookshelfEntry entry = shelf.findByUserIdAndBookId(userId, bookId)
                .orElseGet(() -> new BookshelfEntry(userId, bookId));
        int progress = req.progress() != null ? req.progress() : (entry.getId() == null ? 0 : entry.getProgress());
        if (req.status() == ShelfStatus.COMPLETED) {
            progress = 100;
        }
        entry.setStatus(req.status());
        entry.setProgress(progress);
        shelf.saveAndFlush(entry);
        return toDto(entry, summary);
    }

    /** Idempotent removal. */
    @Transactional
    public void remove(long userId, long bookId) {
        shelf.deleteByUserAndBook(userId, bookId);
    }

    private static BookshelfEntryDto toDto(BookshelfEntry e, BookSummaryDto book) {
        return new BookshelfEntryDto(e.getBookId(), book, e.getStatus(), e.getProgress(), e.getUpdatedAt());
    }
}
