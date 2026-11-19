package com.bookflix.reviews;

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

class InteractionsIntegrationTest extends IntegrationTestBase {

    private long freshBook() throws Exception {
        Session admin = createAdmin();
        JsonNode book = body(mvc.perform(post("/api/admin/books").header("Authorization", admin.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Interaction %s\",\"authorId\":2,\"genreIds\":[4]}".formatted(UUID.randomUUID())))
                .andExpect(status().isCreated()));
        return book.get("id").asLong();
    }

    @Test
    void likeAndUnlikeAreIdempotentAndCounted() throws Exception {
        long bookId = freshBook();
        Session a = registerReader();
        Session b = registerReader();
        mvc.perform(post("/api/books/" + bookId + "/like").header("Authorization", a.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.liked").value(true)).andExpect(jsonPath("$.likeCount").value(1));
        mvc.perform(post("/api/books/" + bookId + "/like").header("Authorization", a.bearer()))
                .andExpect(jsonPath("$.likeCount").value(1));
        mvc.perform(post("/api/books/" + bookId + "/like").header("Authorization", b.bearer()))
                .andExpect(jsonPath("$.likeCount").value(2));
        mvc.perform(get("/api/books/" + bookId).header("Authorization", a.bearer()))
                .andExpect(jsonPath("$.me.liked").value(true)).andExpect(jsonPath("$.likeCount").value(2));
        mvc.perform(delete("/api/books/" + bookId + "/like").header("Authorization", a.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.liked").value(false)).andExpect(jsonPath("$.likeCount").value(1));
        mvc.perform(delete("/api/books/" + bookId + "/like").header("Authorization", a.bearer()))
                .andExpect(jsonPath("$.likeCount").value(1));
        mvc.perform(post("/api/books/999999/like").header("Authorization", a.bearer())).andExpect(status().isNotFound());
        mvc.perform(post("/api/books/" + bookId + "/like")).andExpect(status().isUnauthorized());
    }

    @Test
    void reviewsUpsertAndAggregate() throws Exception {
        long bookId = freshBook();
        Session a = registerReader();
        Session b = registerReader();
        mvc.perform(post("/api/books/" + bookId + "/reviews").header("Authorization", a.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"rating\":4,\"comment\":\"Good\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rating").value(4))
                .andExpect(jsonPath("$.user.name").value("Test Reader"));
        mvc.perform(post("/api/books/" + bookId + "/reviews").header("Authorization", a.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"rating\":5,\"comment\":\"Better on reread\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rating").value(5));
        mvc.perform(post("/api/books/" + bookId + "/reviews").header("Authorization", b.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"rating\":2}"))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/books/" + bookId))
                .andExpect(jsonPath("$.ratingCount").value(2))
                .andExpect(jsonPath("$.averageRating").value(3.5));
        mvc.perform(get("/api/books/" + bookId + "/reviews?size=10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items[0].user.id").exists())
                .andExpect(jsonPath("$.items[0].user.email").doesNotExist());
        mvc.perform(get("/api/books/" + bookId).header("Authorization", b.bearer()))
                .andExpect(jsonPath("$.me.rating").value(2));

        for (String bad : new String[]{"{\"rating\":0}", "{\"rating\":6}", "{}", "{\"rating\":3,\"comment\":\"" + "x".repeat(2001) + "\"}"}) {
            mvc.perform(post("/api/books/" + bookId + "/reviews").header("Authorization", a.bearer())
                            .contentType(MediaType.APPLICATION_JSON).content(bad))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }

        mvc.perform(delete("/api/books/" + bookId + "/reviews/me").header("Authorization", b.bearer()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/books/" + bookId))
                .andExpect(jsonPath("$.ratingCount").value(1))
                .andExpect(jsonPath("$.averageRating").value(5.0));
    }

    @Test
    void bookshelfFlowAndOwnership() throws Exception {
        long bookId = freshBook();
        Session a = registerReader();
        Session b = registerReader();

        mvc.perform(put("/api/bookshelf/" + bookId).header("Authorization", a.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"WANT_TO_READ\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WANT_TO_READ"))
                .andExpect(jsonPath("$.progress").value(0))
                .andExpect(jsonPath("$.book.id").value(bookId));
        mvc.perform(put("/api/bookshelf/" + bookId).header("Authorization", a.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"READING\",\"progress\":42}"))
                .andExpect(jsonPath("$.progress").value(42));
        mvc.perform(put("/api/bookshelf/" + bookId).header("Authorization", a.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"READING\"}"))
                .andExpect(jsonPath("$.progress").value(42));

        JsonNode reading = body(mvc.perform(get("/api/bookshelf?status=READING").header("Authorization", a.bearer()))
                .andExpect(status().isOk()));
        assertThat(reading.size()).isEqualTo(1);
        // another user's shelf is separate
        JsonNode other = body(mvc.perform(get("/api/bookshelf").header("Authorization", b.bearer())).andExpect(status().isOk()));
        assertThat(other.size()).isZero();

        mvc.perform(put("/api/bookshelf/" + bookId).header("Authorization", a.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"COMPLETED\",\"progress\":10}"))
                .andExpect(jsonPath("$.progress").value(100));
        mvc.perform(put("/api/bookshelf/" + bookId).header("Authorization", a.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"READING\",\"progress\":101}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/bookshelf/" + bookId).header("Authorization", a.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"FINISHED\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/bookshelf?status=NOPE").header("Authorization", a.bearer()))
                .andExpect(status().isBadRequest());

        mvc.perform(get("/api/users/me/stats").header("Authorization", a.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookshelf.COMPLETED").value(1))
                .andExpect(jsonPath("$.bookshelf.READING").value(0));

        mvc.perform(delete("/api/bookshelf/" + bookId).header("Authorization", a.bearer())).andExpect(status().isNoContent());
        mvc.perform(delete("/api/bookshelf/" + bookId).header("Authorization", a.bearer())).andExpect(status().isNoContent());
        JsonNode empty = body(mvc.perform(get("/api/bookshelf").header("Authorization", a.bearer())));
        assertThat(empty.size()).isZero();
        mvc.perform(put("/api/bookshelf/999999").header("Authorization", a.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"READING\"}"))
                .andExpect(status().isNotFound());
    }
}
