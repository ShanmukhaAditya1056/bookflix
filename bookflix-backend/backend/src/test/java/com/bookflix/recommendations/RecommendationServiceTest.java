package com.bookflix.recommendations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import com.bookflix.authors.AuthorDtos.AuthorRef;
import com.bookflix.books.BookDtos.BookSummaryDto;
import com.bookflix.books.BookService;
import com.bookflix.recommendations.MlClient.MlUnavailableException;
import com.bookflix.recommendations.MlDtos.ForYouRequest;
import com.bookflix.recommendations.MlDtos.MlRecommendation;
import com.bookflix.recommendations.MlDtos.MlRecommendationResponse;
import com.bookflix.recommendations.MlDtos.RatingSignal;
import com.bookflix.recommendations.RecommendationDtos.RecommendationResponse;
import com.bookflix.recommendations.RecommendationQueries.Ranked;

class RecommendationServiceTest {

    private MlClient ml;
    private RecommendationQueries queries;
    private BookService books;
    private RecommendationService service;

    @BeforeEach
    void setUp() {
        ml = mock(MlClient.class);
        queries = mock(RecommendationQueries.class);
        books = mock(BookService.class);
        service = new RecommendationService(ml, queries, books);
        when(ml.usable()).thenReturn(true);
        when(queries.loadSignals(1L)).thenReturn(new UserSignals(List.of(3L), List.of(10L),
                List.of(new RatingSignal(11L, 1)), List.of()));
        // every id except 999 (deleted) resolves to a book
        when(books.summariesById(anyList())).thenAnswer(inv -> {
            List<Long> ids = inv.getArgument(0);
            Map<Long, BookSummaryDto> out = new LinkedHashMap<>();
            ids.stream().filter(id -> id != 999L).forEach(id -> out.put(id, book(id)));
            return out;
        });
    }

    @Test
    void mlResultsAreJoinedFilteredAndIdentityComesFromCaller() {
        when(ml.forYou(any())).thenReturn(new MlRecommendationResponse(List.of(
                new MlRecommendation(20L, 0.9, "Similar to X", "CONTENT_HYBRID"),
                new MlRecommendation(999L, 0.8, "deleted", "CONTENT_HYBRID"),
                new MlRecommendation(10L, 0.7, "already liked", "CONTENT_HYBRID"),
                new MlRecommendation(20L, 0.6, "dup", "CONTENT_HYBRID"),
                new MlRecommendation(21L, Double.NaN, "bad score", "CONTENT_HYBRID")),
                "CONTENT_HYBRID", "v1"));

        RecommendationResponse res = service.forYou(1L, 3);

        assertThat(res.degraded()).isFalse();
        assertThat(res.modelVersion()).isEqualTo("v1");
        assertThat(res.items()).extracting(i -> i.book().id()).containsExactly(20L, 21L);
        assertThat(res.items().get(1).score()).isZero(); // NaN sanitised
        ArgumentCaptor<ForYouRequest> req = ArgumentCaptor.forClass(ForYouRequest.class);
        Mockito.verify(ml).forYou(req.capture());
        assertThat(req.getValue().excludeBookIds()).contains(10L, 11L);
        assertThat(req.getValue().likedBookIds()).containsExactly(10L);
    }

    @Test
    void mlFailureFallsBackWithoutThrowing() {
        when(ml.forYou(any())).thenThrow(new MlUnavailableException("timeout", null));
        when(queries.genresOfBooks(anyCollection())).thenReturn(List.of());
        when(queries.byGenreOverlap(anyCollection(), anyCollection(), anyInt()))
                .thenReturn(List.of(new Ranked(30L, 0.8)));
        when(queries.popular(anyCollection(), anyInt())).thenReturn(List.of(new Ranked(31L, 0.5)));

        RecommendationResponse res = service.forYou(1L, 2);

        assertThat(res.degraded()).isTrue();
        assertThat(res.strategy()).isEqualTo(RecommendationService.FALLBACK_GENRE);
        assertThat(res.items()).extracting(i -> i.book().id()).containsExactly(30L, 31L);
    }

    @Test
    void mlDisabledUsesFallbackAndIsFlaggedDegraded() {
        when(ml.usable()).thenReturn(false);
        when(queries.genresOfBooks(anyCollection())).thenReturn(List.of());
        when(queries.byGenreOverlap(anyCollection(), anyCollection(), anyInt())).thenReturn(List.of());
        when(queries.popular(anyCollection(), anyInt())).thenReturn(List.of(new Ranked(40L, 1.0)));

        RecommendationResponse res = service.forYou(1L, 1);

        assertThat(res.degraded()).isTrue();
        assertThat(res.strategy()).isEqualTo(RecommendationService.FALLBACK_POPULARITY);
        Mockito.verify(ml, Mockito.never()).forYou(any());
    }

    @Test
    void becauseYouLikedWithoutPositiveSignalsIsEmpty() {
        when(queries.loadSignals(2L)).thenReturn(new UserSignals(List.of(), List.of(), List.of(), List.of()));
        when(queries.latestPositiveAnchor(any())).thenReturn(null);
        RecommendationResponse empty = service.becauseYouLiked(2L, 5);
        assertThat(empty.items()).isEmpty();
        assertThat(empty.strategy()).isEqualTo("NONE");
        assertThat(empty.sourceBook()).isNull();
    }

    private static BookSummaryDto book(long id) {
        return new BookSummaryDto(id, "Book " + id, new AuthorRef(1L, "A"), null, 1900, List.of(), 0, 0, 0);
    }
}
