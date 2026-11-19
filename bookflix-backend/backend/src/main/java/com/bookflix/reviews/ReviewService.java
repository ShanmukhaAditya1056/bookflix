package com.bookflix.reviews;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bookflix.books.BookRepository;
import com.bookflix.common.ApiException;
import com.bookflix.common.ErrorCode;
import com.bookflix.common.PageResponse;
import com.bookflix.reviews.ReviewDtos.ReviewDto;
import com.bookflix.reviews.ReviewDtos.ReviewRequest;
import com.bookflix.users.User;
import com.bookflix.users.UserRepository;

@Service
public class ReviewService {

    private final ReviewRepository reviews;
    private final BookRepository books;
    private final UserRepository users;

    public ReviewService(ReviewRepository reviews, BookRepository books, UserRepository users) {
        this.reviews = reviews;
        this.books = books;
        this.users = users;
    }

    public record UpsertResult(ReviewDto review, boolean created) {
    }

    @Transactional(readOnly = true)
    public PageResponse<ReviewDto> list(long bookId, int page, int size) {
        requireActiveBook(bookId);
        PageRequest pr = PageRequest.of(page, size, Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("id")));
        return PageResponse.from(reviews.findByBookId(bookId, pr), ReviewDto::from);
    }

    /** One review per user per book: a second POST updates the existing review. */
    @Transactional
    public UpsertResult upsert(long userId, long bookId, ReviewRequest req) {
        requireActiveBook(bookId);
        User user = users.findById(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED, "User no longer exists"));
        Review review = reviews.findByUserAndBook(userId, bookId).orElse(null);
        boolean created = review == null;
        if (created) {
            review = new Review(user, bookId);
        }
        review.setRating(req.rating());
        review.setComment(req.comment() == null || req.comment().isBlank() ? null : req.comment().trim());
        reviews.saveAndFlush(review);
        books.refreshRatingStats(bookId);
        return new UpsertResult(ReviewDto.from(review), created);
    }

    @Transactional
    public void deleteMine(long userId, long bookId) {
        requireActiveBook(bookId);
        reviews.findByUserAndBook(userId, bookId).ifPresent(r -> {
            reviews.delete(r);
            reviews.flush();
            books.refreshRatingStats(bookId);
        });
    }

    private void requireActiveBook(long bookId) {
        if (books.findActiveById(bookId).isEmpty()) {
            throw ApiException.notFound("Book");
        }
    }
}
