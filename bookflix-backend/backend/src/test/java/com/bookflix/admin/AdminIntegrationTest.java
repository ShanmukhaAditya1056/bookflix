package com.bookflix.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.bookflix.IntegrationTestBase;

import tools.jackson.databind.JsonNode;

class AdminIntegrationTest extends IntegrationTestBase {

    @Test
    void readersAndAnonymousCannotUseAdminEndpoints() throws Exception {
        Session reader = registerReader();
        mvc.perform(get("/api/admin/stats").header("Authorization", reader.bearer()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(post("/api/admin/genres").header("Authorization", reader.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Nope\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/admin/books/1").header("Authorization", reader.bearer()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/stats")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/admin/recommendations/rebuild")).andExpect(status().isUnauthorized());
    }

    @Test
    void adminCrudForGenresAuthorsAndBooks() throws Exception {
        Session admin = createAdmin();
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        JsonNode genre = body(mvc.perform(post("/api/admin/genres").header("Authorization", admin.bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Cozy Mystery " + suffix + "\"}"))
                .andExpect(status().isCreated()));
        assertThat(genre.get("slug").asString()).isEqualTo("cozy-mystery-" + suffix);
        mvc.perform(post("/api/admin/genres").header("Authorization", admin.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"cozy mystery " + suffix + "\"}"))
                .andExpect(status().isConflict());

        JsonNode author = body(mvc.perform(post("/api/admin/authors").header("Authorization", admin.bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Demo Author " + suffix + "\",\"bio\":\"x\"}"))
                .andExpect(status().isCreated()));
        long authorId = author.get("id").asLong();
        long genreId = genre.get("id").asLong();

        String bookJson = """
                {"title":"Demo Book %s","description":"A lighthouse keeper solves riddles.","authorId":%d,
                 "isbn":"978-0-00-000000-%s","publishedYear":2020,"language":"en","genreIds":[%d,3]}"""
                .formatted(suffix, authorId, String.valueOf(Math.abs(suffix.hashCode()) % 10), genreId);
        JsonNode book = body(mvc.perform(post("/api/admin/books").header("Authorization", admin.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(bookJson)).andExpect(status().isCreated()));
        long bookId = book.get("id").asLong();
        assertThat(book.get("genres").size()).isEqualTo(2);
        assertThat(book.get("isbn").asString()).doesNotContain("-");

        // searchable immediately by author name
        JsonNode found = body(mvc.perform(get("/api/books").param("q", "Demo Author " + suffix)).andExpect(status().isOk()));
        assertThat(found.get("totalElements").asLong()).isEqualTo(1);

        // update + author rename keep search in sync
        mvc.perform(put("/api/admin/authors/" + authorId).header("Authorization", admin.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Renamedauthor" + suffix + "\"}"))
                .andExpect(status().isOk());
        JsonNode renamed = body(mvc.perform(get("/api/books").param("q", "Renamedauthor" + suffix)).andExpect(status().isOk()));
        assertThat(renamed.get("totalElements").asLong()).isEqualTo(1);

        mvc.perform(put("/api/admin/books/" + bookId).header("Authorization", admin.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(bookJson.replace("Demo Book", "Edited Book")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Edited Book " + suffix));

        // validation
        mvc.perform(post("/api/admin/books").header("Authorization", admin.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"\",\"authorId\":" + authorId + ",\"genreIds\":[],\"coverUrl\":\"javascript:alert(1)\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // genre and author in use cannot be deleted
        mvc.perform(delete("/api/admin/genres/" + genreId).header("Authorization", admin.bearer()))
                .andExpect(status().isConflict());
        mvc.perform(delete("/api/admin/authors/" + authorId).header("Authorization", admin.bearer()))
                .andExpect(status().isConflict());

        mvc.perform(get("/api/admin/stats").header("Authorization", admin.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.books").isNumber())
                .andExpect(jsonPath("$.model.enabled").value(false));
        mvc.perform(post("/api/admin/recommendations/rebuild").header("Authorization", admin.bearer()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ML_UNAVAILABLE"));
    }

    @Test
    void deletingABookWithInteractionsSoftDeletesIt() throws Exception {
        Session admin = createAdmin();
        Session reader = registerReader();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        JsonNode book = body(mvc.perform(post("/api/admin/books").header("Authorization", admin.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Doomed %s\",\"authorId\":1,\"genreIds\":[1]}".formatted(suffix)))
                .andExpect(status().isCreated()));
        long bookId = book.get("id").asLong();

        mvc.perform(post("/api/books/" + bookId + "/like").header("Authorization", reader.bearer())).andExpect(status().isOk());
        mvc.perform(post("/api/books/" + bookId + "/reviews").header("Authorization", reader.bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"rating\":5,\"comment\":\"great\"}"))
                .andExpect(status().isCreated());
        mvc.perform(put("/api/bookshelf/" + bookId).header("Authorization", reader.bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"READING\",\"progress\":10}"))
                .andExpect(status().isOk());

        mvc.perform(delete("/api/admin/books/" + bookId).header("Authorization", admin.bearer()))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/books/" + bookId)).andExpect(status().isNotFound());
        mvc.perform(delete("/api/admin/books/" + bookId).header("Authorization", admin.bearer()))
                .andExpect(status().isNotFound());
        JsonNode search = body(mvc.perform(get("/api/books").param("q", "Doomed " + suffix)).andExpect(status().isOk()));
        assertThat(search.get("totalElements").asLong()).isZero();
        JsonNode shelf = body(mvc.perform(get("/api/bookshelf").header("Authorization", reader.bearer())).andExpect(status().isOk()));
        assertThat(shelf.findValues("bookId").stream().map(JsonNode::asLong)).doesNotContain(bookId);
        mvc.perform(post("/api/books/" + bookId + "/like").header("Authorization", reader.bearer()))
                .andExpect(status().isNotFound());

        // interaction history is retained
        long reviews = jdbc.sql("SELECT COUNT(*) FROM reviews WHERE book_id = ?").param(bookId).query(Long.class).single();
        long likes = jdbc.sql("SELECT COUNT(*) FROM user_likes WHERE book_id = ?").param(bookId).query(Long.class).single();
        assertThat(reviews).isEqualTo(1);
        assertThat(likes).isEqualTo(1);
    }
}
