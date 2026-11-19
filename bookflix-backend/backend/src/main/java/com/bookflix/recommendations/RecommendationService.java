package com.bookflix.recommendations;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.bookflix.books.BookDtos.BookSummaryDto;
import com.bookflix.books.BookService;
import com.bookflix.recommendations.MlClient.MlUnavailableException;
import com.bookflix.recommendations.MlDtos.ForYouRequest;
import com.bookflix.recommendations.MlDtos.MlRecommendation;
import com.bookflix.recommendations.MlDtos.MlRecommendationResponse;
import com.bookflix.recommendations.MlDtos.SimilarRequest;
import com.bookflix.recommendations.RecommendationDtos.RecommendationItemDto;
import com.bookflix.recommendations.RecommendationDtos.RecommendationResponse;
import com.bookflix.recommendations.RecommendationQueries.Ranked;

/**
 * Orchestrates recommendation shelves. Identity always comes from the authenticated principal.
 * ML results are joined with live book metadata (dropping deleted/unknown ids); any ML failure
 * degrades to SQL genre/popularity ranking instead of returning an error.
 */
@Service
public class RecommendationService {

    private static final Logger log = LoggerFactory.getLogger(RecommendationService.class);

    public static final String FALLBACK_GENRE = "FALLBACK_GENRE_POPULARITY";
    public static final String FALLBACK_POPULARITY = "FALLBACK_POPULARITY";
    public static final String FALLBACK_SIMILAR = "FALLBACK_METADATA_SIMILARITY";

    private final MlClient ml;
    private final RecommendationQueries queries;
    private final BookService books;

    public RecommendationService(MlClient ml, RecommendationQueries queries, BookService books) {
        this.ml = ml;
        this.queries = queries;
        this.books = books;
    }

    public RecommendationResponse forYou(long userId, int limit) {
        UserSignals signals = queries.loadSignals(userId);
        Set<Long> exclude = new LinkedHashSet<>(signals.interactedBookIds());

        if (ml.usable()) {
            try {
                MlRecommendationResponse resp = ml.forYou(new ForYouRequest(signals.preferredGenreIds(),
                        signals.likedBookIds(), signals.ratings(), signals.shelf(), List.copyOf(exclude), limit, 0));
                List<RecommendationItemDto> items = join(resp.items(), exclude, limit);
                if (!items.isEmpty()) {
                    topUpWithFallback(items, signals, exclude, limit);
                    return new RecommendationResponse("FOR_YOU", resp.strategy(), false, resp.modelVersion(), null, items);
                }
            } catch (MlUnavailableException e) {
                log.warn("for-you: ML unavailable ({}), using fallback ranking", e.getMessage());
                return fallbackForYou(signals, exclude, limit, true);
            }
        }
        return fallbackForYou(signals, exclude, limit, !ml.usable());
    }

    public RecommendationResponse similar(long bookId, Long userIdOrNull, int limit) {
        BookSummaryDto source = BookSummaryDto.from(books.requireActive(bookId));
        Set<Long> exclude = new LinkedHashSet<>();
        exclude.add(bookId);
        if (userIdOrNull != null) {
            exclude.addAll(queries.loadSignals(userIdOrNull).dislikedBookIds());
        }
        return similarShelf("SIMILAR", source, exclude, limit, null);
    }

    public RecommendationResponse becauseYouLiked(long userId, int limit) {
        UserSignals signals = queries.loadSignals(userId);
        Long anchor = queries.latestPositiveAnchor(signals);
        if (anchor == null) {
            return new RecommendationResponse("BECAUSE_YOU_LIKED", "NONE", false, null, null, List.of());
        }
        BookSummaryDto source = BookSummaryDto.from(books.requireActive(anchor));
        Set<Long> exclude = new LinkedHashSet<>(signals.interactedBookIds());
        exclude.add(anchor);
        return similarShelf("BECAUSE_YOU_LIKED", source, exclude, limit, "Because you liked " + source.title());
    }

    public RecommendationResponse trending(Long userIdOrNull, int limit) {
        List<Ranked> ranked = queries.trending(Set.of(), limit);
        List<RecommendationItemDto> items = toItems(ranked, r -> r.score() > 0.2 ? "Trending on Bookflix this month"
                : "Popular with Bookflix readers", "TRENDING", limit);
        return new RecommendationResponse("TRENDING", "TRENDING", false, null, null, items);
    }

    public RecommendationResponse byGenres(long userId, int limit) {
        UserSignals signals = queries.loadSignals(userId);
        List<Long> genres = signals.preferredGenreIds();
        if (genres.isEmpty()) {
            genres = queries.genresOfBooks(signals.positiveBookIds());
        }
        if (genres.isEmpty()) {
            return new RecommendationResponse("BY_GENRES", "NONE", false, null, null, List.of());
        }
        List<Ranked> ranked = queries.byGenreOverlap(genres, signals.interactedBookIds(), limit);
        List<RecommendationItemDto> items = toItems(ranked, r -> "Matches your favourite genres", "GENRE_MATCH", limit);
        return new RecommendationResponse("BY_GENRES", "GENRE_MATCH", false, null, null, items);
    }

    // ---- internals ----

    private RecommendationResponse similarShelf(String shelf, BookSummaryDto source, Set<Long> exclude, int limit,
                                                String reasonOverride) {
        if (ml.usable()) {
            try {
                MlRecommendationResponse resp = ml.similar(
                        new SimilarRequest(source.id(), List.copyOf(exclude), limit, 0));
                List<RecommendationItemDto> items = join(resp.items(), exclude, limit);
                if (reasonOverride != null) {
                    items = items.stream().map(i -> new RecommendationItemDto(i.book(), i.score(), reasonOverride,
                            i.strategy())).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
                }
                if (!items.isEmpty()) {
                    return new RecommendationResponse(shelf, resp.strategy(), false, resp.modelVersion(), source, items);
                }
            } catch (MlUnavailableException e) {
                log.warn("{}: ML unavailable ({}), using metadata fallback", shelf, e.getMessage());
            }
        }
        List<Ranked> ranked = queries.similarByMetadata(source.id(), exclude, limit);
        String reason = reasonOverride != null ? reasonOverride : "Shares genres with " + source.title();
        List<RecommendationItemDto> items = toItems(ranked, r -> reason, FALLBACK_SIMILAR, limit);
        return new RecommendationResponse(shelf, FALLBACK_SIMILAR, true, null, source, items);
    }

    private RecommendationResponse fallbackForYou(UserSignals signals, Set<Long> exclude, int limit, boolean degraded) {
        List<RecommendationItemDto> items = new ArrayList<>();
        topUpWithFallback(items, signals, exclude, limit);
        String strategy = items.stream().anyMatch(i -> FALLBACK_GENRE.equals(i.strategy()))
                ? FALLBACK_GENRE : FALLBACK_POPULARITY;
        return new RecommendationResponse("FOR_YOU", strategy, degraded, null, null, items);
    }

    /** Fills {@code items} up to {@code limit} with genre-overlap, then popularity, ranking. */
    private void topUpWithFallback(List<RecommendationItemDto> items, UserSignals signals, Set<Long> exclude,
                                   int limit) {
        if (items.size() >= limit) {
            return;
        }
        Set<Long> seen = new LinkedHashSet<>(exclude);
        items.forEach(i -> seen.add(i.book().id()));

        Set<Long> genres = new LinkedHashSet<>(signals.preferredGenreIds());
        genres.addAll(queries.genresOfBooks(signals.positiveBookIds()));
        List<Ranked> byGenre = queries.byGenreOverlap(genres, seen, limit - items.size());
        items.addAll(toItems(byGenre, r -> "Matches genres you enjoy", FALLBACK_GENRE, limit - items.size()));
        items.forEach(i -> seen.add(i.book().id()));

        if (items.size() < limit) {
            List<Ranked> popular = queries.popular(seen, limit - items.size());
            items.addAll(toItems(popular, r -> "Popular with Bookflix readers", FALLBACK_POPULARITY,
                    limit - items.size()));
        }
    }

    private List<RecommendationItemDto> join(List<MlRecommendation> recs, Set<Long> exclude, int limit) {
        if (recs == null || recs.isEmpty()) {
            return new ArrayList<>();
        }
        List<Long> ids = recs.stream().map(MlRecommendation::bookId).filter(id -> !exclude.contains(id)).toList();
        Map<Long, BookSummaryDto> summaries = books.summariesById(ids);
        List<RecommendationItemDto> out = new ArrayList<>();
        Set<Long> seen = new LinkedHashSet<>();
        for (MlRecommendation r : recs) {
            BookSummaryDto b = summaries.get(r.bookId());
            if (b == null || exclude.contains(r.bookId()) || !seen.add(r.bookId())) {
                continue; // deleted, unknown, excluded or duplicate
            }
            double score = Double.isFinite(r.score()) ? RecommendationQueries.round(r.score()) : 0.0;
            out.add(new RecommendationItemDto(b, score, r.reason(), r.strategy()));
            if (out.size() == limit) {
                break;
            }
        }
        return out;
    }

    private List<RecommendationItemDto> toItems(List<Ranked> ranked, java.util.function.Function<Ranked, String> reason,
                                                String strategy, int limit) {
        Map<Long, BookSummaryDto> summaries = books.summariesById(ranked.stream().map(Ranked::bookId).toList());
        List<RecommendationItemDto> out = new ArrayList<>();
        for (Ranked r : ranked) {
            BookSummaryDto b = summaries.get(r.bookId());
            if (b != null && out.size() < limit) {
                out.add(new RecommendationItemDto(b, r.score(), reason.apply(r), strategy));
            }
        }
        return out;
    }
}
