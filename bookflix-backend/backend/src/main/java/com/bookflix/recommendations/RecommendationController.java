package com.bookflix.recommendations;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.bookflix.common.CurrentUser;
import com.bookflix.recommendations.RecommendationDtos.RecommendationResponse;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@RestController
@RequestMapping("/api/recommendations")
@Validated
public class RecommendationController {

    private final RecommendationService service;

    public RecommendationController(RecommendationService service) {
        this.service = service;
    }

    @GetMapping("/for-you")
    public RecommendationResponse forYou(@AuthenticationPrincipal Jwt jwt,
                                         @RequestParam(defaultValue = "12") @Min(1) @Max(50) int limit) {
        return service.forYou(CurrentUser.id(jwt), limit);
    }

    @GetMapping("/because-you-liked")
    public RecommendationResponse becauseYouLiked(@AuthenticationPrincipal Jwt jwt,
                                                  @RequestParam(defaultValue = "12") @Min(1) @Max(50) int limit) {
        return service.becauseYouLiked(CurrentUser.id(jwt), limit);
    }

    @GetMapping("/genres")
    public RecommendationResponse byGenres(@AuthenticationPrincipal Jwt jwt,
                                           @RequestParam(defaultValue = "12") @Min(1) @Max(50) int limit) {
        return service.byGenres(CurrentUser.id(jwt), limit);
    }

    /** Public; personal exclusions (books rated <= 2) apply when a valid token is sent. */
    @GetMapping("/similar/{bookId}")
    public RecommendationResponse similar(@PathVariable long bookId,
                                          @RequestParam(defaultValue = "12") @Min(1) @Max(50) int limit) {
        return service.similar(bookId, CurrentUser.idOrNull(), limit);
    }

    /** Public; Spring is the canonical owner of Trending (SQL over recent activity). */
    @GetMapping("/trending")
    public RecommendationResponse trending(@RequestParam(defaultValue = "12") @Min(1) @Max(50) int limit) {
        return service.trending(CurrentUser.idOrNull(), limit);
    }
}
