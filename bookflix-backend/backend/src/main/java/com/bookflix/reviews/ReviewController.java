package com.bookflix.reviews;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.bookflix.common.CurrentUser;
import com.bookflix.common.PageResponse;
import com.bookflix.reviews.ReviewDtos.ReviewDto;
import com.bookflix.reviews.ReviewDtos.ReviewRequest;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@RestController
@RequestMapping("/api/books/{bookId}/reviews")
@Validated
public class ReviewController {

    private final ReviewService reviewService;

    public ReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @GetMapping
    public PageResponse<ReviewDto> list(@PathVariable long bookId,
                                        @RequestParam(defaultValue = "0") @Min(0) int page,
                                        @RequestParam(defaultValue = "10") @Min(1) @Max(50) int size) {
        return reviewService.list(bookId, page, size);
    }

    /** 201 when a review is created, 200 when the caller's existing review is updated. */
    @PostMapping
    public ResponseEntity<ReviewDto> upsert(@AuthenticationPrincipal Jwt jwt, @PathVariable long bookId,
                                            @Valid @RequestBody ReviewRequest req) {
        ReviewService.UpsertResult result = reviewService.upsert(CurrentUser.id(jwt), bookId, req);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.review());
    }

    @DeleteMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMine(@AuthenticationPrincipal Jwt jwt, @PathVariable long bookId) {
        reviewService.deleteMine(CurrentUser.id(jwt), bookId);
    }
}
