package com.bookflix.recommendations;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.bookflix.recommendations.MlDtos.RatingSignal;
import com.bookflix.recommendations.MlDtos.ShelfSignal;

/** Trusted interaction features for one user, loaded server-side from MySQL. */
public record UserSignals(List<Long> preferredGenreIds, List<Long> likedBookIds, List<RatingSignal> ratings,
                          List<ShelfSignal> shelf) {

    /** Books the user already knows about (liked, rated or shelved); excluded from discovery shelves. */
    public Set<Long> interactedBookIds() {
        Set<Long> ids = new LinkedHashSet<>(likedBookIds);
        ratings.forEach(r -> ids.add(r.bookId()));
        shelf.forEach(s -> ids.add(s.bookId()));
        return ids;
    }

    public Set<Long> dislikedBookIds() {
        Set<Long> ids = new LinkedHashSet<>();
        ratings.stream().filter(r -> r.rating() <= 2).forEach(r -> ids.add(r.bookId()));
        return ids;
    }

    /** Liked, rated >= 4, or READING/COMPLETED, minus anything rated <= 2. */
    public Set<Long> positiveBookIds() {
        Set<Long> ids = new LinkedHashSet<>(likedBookIds);
        ratings.stream().filter(r -> r.rating() >= 4).forEach(r -> ids.add(r.bookId()));
        shelf.stream().filter(s -> !"WANT_TO_READ".equals(s.status())).forEach(s -> ids.add(s.bookId()));
        ids.removeAll(dislikedBookIds());
        return ids;
    }
}
