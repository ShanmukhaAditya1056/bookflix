package com.bookflix.recommendations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.bookflix.IntegrationTestBase;

import tools.jackson.databind.JsonNode;

/** ML is disabled in the test profile, so these exercise the SQL fallback paths end to end. */
class RecommendationFallbackIntegrationTest extends IntegrationTestBase {

    @Test
    void coldStartForYouUsesGenresAndIsDegradedNotAnError() throws Exception {
        Session s = registerReader();
        mvc.perform(put("/api/users/me/genres").header("Authorization", s.bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"genreIds\":[6]}")).andExpect(status().isOk());

        JsonNode res = body(mvc.perform(get("/api/recommendations/for-you?limit=5").header("Authorization", s.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shelf").value("FOR_YOU"))
                .andExpect(jsonPath("$.degraded").value(true))
                .andExpect(jsonPath("$.strategy").value("FALLBACK_GENRE_POPULARITY")));
        assertThat(res.get("items").size()).isEqualTo(5);
        for (JsonNode item : res.get("items")) {
            List<Long> genres = new ArrayList<>();
            item.at("/book/genres").forEach(g -> genres.add(g.get("id").asLong()));
            assertThat(genres).contains(6L);
            assertThat(item.get("reason").asString()).isNotBlank();
            assertThat(Double.isFinite(item.get("score").asDouble())).isTrue();
        }
    }

    @Test
    void forYouExcludesBooksTheUserAlreadyInteractedWith() throws Exception {
        Session s = registerReader();
        mvc.perform(post("/api/books/7/like").header("Authorization", s.bearer())).andExpect(status().isOk());
        mvc.perform(put("/api/bookshelf/82").header("Authorization", s.bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"WANT_TO_READ\"}")).andExpect(status().isOk());
        JsonNode res = body(mvc.perform(get("/api/recommendations/for-you?limit=50").header("Authorization", s.bearer()))
                .andExpect(status().isOk()));
        List<Long> ids = bookIds(res);
        assertThat(ids).doesNotContain(7L, 82L).doesNotHaveDuplicates().hasSize(50);
    }

    @Test
    void becauseYouLikedAndSimilar() throws Exception {
        Session s = registerReader();
        mvc.perform(get("/api/recommendations/because-you-liked").header("Authorization", s.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.sourceBook").doesNotExist());

        mvc.perform(post("/api/books/15/like").header("Authorization", s.bearer())).andExpect(status().isOk());
        JsonNode byl = body(mvc.perform(get("/api/recommendations/because-you-liked?limit=5").header("Authorization", s.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceBook.id").value(15))
                .andExpect(jsonPath("$.items[0].reason").value("Because you liked A Study in Scarlet")));
        assertThat(bookIds(byl)).doesNotContain(15L).isNotEmpty();

        JsonNode similar = body(mvc.perform(get("/api/recommendations/similar/16?limit=5")).andExpect(status().isOk())
                .andExpect(jsonPath("$.shelf").value("SIMILAR"))
                .andExpect(jsonPath("$.sourceBook.title").value("The Hound of the Baskervilles")));
        assertThat(bookIds(similar)).doesNotContain(16L).contains(15L); // same author + Mystery
        mvc.perform(get("/api/recommendations/similar/999999")).andExpect(status().isNotFound());
    }

    @Test
    void trendingIsPublicAndReflectsRecentActivity() throws Exception {
        Session a = registerReader();
        Session b = registerReader();
        mvc.perform(post("/api/books/75/like").header("Authorization", a.bearer())).andExpect(status().isOk());
        mvc.perform(post("/api/books/75/like").header("Authorization", b.bearer())).andExpect(status().isOk());
        mvc.perform(post("/api/books/75/reviews").header("Authorization", a.bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"rating\":5}")).andExpect(status().isCreated());
        JsonNode res = body(mvc.perform(get("/api/recommendations/trending?limit=10")).andExpect(status().isOk())
                .andExpect(jsonPath("$.strategy").value("TRENDING"))
                .andExpect(jsonPath("$.degraded").value(false)));
        assertThat(bookIds(res)).contains(75L);
        mvc.perform(get("/api/recommendations/trending?limit=0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/recommendations/trending?limit=51")).andExpect(status().isBadRequest());
    }

    @Test
    void basedOnYourGenres() throws Exception {
        Session s = registerReader();
        mvc.perform(get("/api/recommendations/genres").header("Authorization", s.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(put("/api/users/me/genres").header("Authorization", s.bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"genreIds\":[10]}")).andExpect(status().isOk());
        JsonNode res = body(mvc.perform(get("/api/recommendations/genres?limit=20").header("Authorization", s.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.strategy").value("GENRE_MATCH")));
        assertThat(res.get("items").size()).isGreaterThan(0);
        res.get("items").forEach(i -> {
            List<Long> g = new ArrayList<>();
            i.at("/book/genres").forEach(x -> g.add(x.get("id").asLong()));
            assertThat(g).contains(10L);
        });
    }

    private static List<Long> bookIds(JsonNode res) {
        List<Long> ids = new ArrayList<>();
        res.get("items").forEach(i -> ids.add(i.at("/book/id").asLong()));
        return ids;
    }
}
