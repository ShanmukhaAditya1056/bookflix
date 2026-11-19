package com.bookflix.books;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bookflix.books.BookDtos.LikeStatusDto;
import com.bookflix.common.ApiException;

@Service
public class LikeService {

    private final BookRepository books;
    private final UserLikeRepository likes;

    public LikeService(BookRepository books, UserLikeRepository likes) {
        this.books = books;
        this.likes = likes;
    }

    /** Idempotent: liking twice keeps a single like. */
    @Transactional
    public LikeStatusDto like(long userId, long bookId) {
        requireActive(bookId);
        likes.insertIgnore(userId, bookId);
        books.refreshLikeCount(bookId);
        return new LikeStatusDto(bookId, true, books.likeCountOf(bookId));
    }

    /** Idempotent: unliking a book that is not liked is a no-op. */
    @Transactional
    public LikeStatusDto unlike(long userId, long bookId) {
        requireActive(bookId);
        likes.deleteByUserAndBook(userId, bookId);
        books.refreshLikeCount(bookId);
        return new LikeStatusDto(bookId, false, books.likeCountOf(bookId));
    }

    private void requireActive(long bookId) {
        if (books.findActiveById(bookId).isEmpty()) {
            throw ApiException.notFound("Book");
        }
    }
}
