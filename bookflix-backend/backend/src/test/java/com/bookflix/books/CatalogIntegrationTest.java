package com.bookflix.books;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.bookflix.IntegrationTestBase;

import tools.jackson.databind.JsonNode;

class CatalogIntegrationTest extends IntegrationTestBase {

    @Test
    void seededCatalogIsPagedAndPublic() throws Exception {
        JsonNode page = body(mvc.perform(get("/api/books?page=0&size=10")).andExpect(status().isOk()));
        assertThat(page.get("items").size()).isEqualTo(10);
        assertThat(page.get("totalElements").asLong()).isGreaterThanOrEqualTo(60);
        assertThat(page.get("page").asInt()).isZero();
        JsonNode first = page.get("items").get(0);
        assertThat(first.has("author")).isTrue();
        assertThat(first.get("genres").isArray()).isTrue();
        assertThat(first.has("description")).isFalse(); // summaries stay small
    }

    @Test
    void searchMatchesTitleDescriptionAndAuthor() throws Exception {
        JsonNode byTitle = body(mvc.perform(get("/api/books?q=sherlock")).andExpect(status().isOk()));
        assertThat(titles(byTitle)).contains("The Adventures of Sherlock Holmes");

        JsonNode byAuthor = body(mvc.perform(get("/api/books?q=tolstoy")).andExpect(status().isOk()));
        assertThat(titles(byAuthor)).containsExactlyInAnyOrder("Anna Karenina", "War and Peace");

        JsonNode prefix = body(mvc.perform(get("/api/books?q=vampir")).andExpect(status().isOk()));
        assertThat(titles(prefix)).contains("Dracula", "Carmilla");

        JsonNode crossTable = body(mvc.perform(get("/api/books").param("q", "war tolstoy")).andExpect(status().isOk()));
        assertThat(titles(crossTable)).containsExactly("War and Peace");

        JsonNode shortQuery = body(mvc.perform(get("/api/books?q=Em")).andExpect(status().isOk()));
        assertThat(titles(shortQuery)).contains("Emma");

        JsonNode nothing = body(mvc.perform(get("/api/books?q=zzzqqqxxx")).andExpect(status().isOk()));
        assertThat(nothing.get("totalElements").asLong()).isZero();
    }

    @Test
    void genreFilterAndSorting() throws Exception {
        JsonNode horror = body(mvc.perform(get("/api/books?genreId=6&size=50&sort=title")).andExpect(status().isOk()));
        for (JsonNode b : horror.get("items")) {
            List<Long> ids = new ArrayList<>();
            b.get("genres").forEach(g -> ids.add(g.get("id").asLong()));
            assertThat(ids).contains(6L);
        }
        List<String> t = titles(horror);
        assertThat(t).isSortedAccordingTo(String.CASE_INSENSITIVE_ORDER);

        JsonNode byYear = body(mvc.perform(get("/api/books?sort=year_desc&size=5")).andExpect(status().isOk()));
        assertThat(byYear.get("items").get(0).get("publishedYear").asInt()).isGreaterThanOrEqualTo(1920);

        mvc.perform(get("/api/books?sort=bogus")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        mvc.perform(get("/api/books?size=500")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(get("/api/books?page=abc")).andExpect(status().isBadRequest());
    }

    @Test
    void detailGenresAndAuthors() throws Exception {
        mvc.perform(get("/api/books/7")).andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Dracula"))
                .andExpect(jsonPath("$.description").isNotEmpty())
                .andExpect(jsonPath("$.author.name").value("Bram Stoker"))
                .andExpect(jsonPath("$.me").doesNotExist());
        mvc.perform(get("/api/books/999999")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(get("/api/genres")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(16)));
        mvc.perform(get("/api/authors?q=bront")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    private static List<String> titles(JsonNode page) {
        List<String> out = new ArrayList<>();
        page.get("items").forEach(b -> out.add(b.get("title").asString()));
        return out;
    }
}
