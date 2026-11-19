package com.bookflix.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.bookflix.IntegrationTestBase;

class AuthIntegrationTest extends IntegrationTestBase {

    @Test
    void registerReturnsTokenAndReaderRoleEvenIfRoleIsSupplied() throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                        {"name":"Ada","email":"Ada.Role@Example.test","password":"long-enough-1","role":"ADMIN"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.user.role").value("READER"))
                .andExpect(jsonPath("$.user.email").value("ada.role@example.test"))
                .andExpect(jsonPath("$.user.onboardingCompleted").value(false));
        String hash = jdbc.sql("SELECT password_hash FROM users WHERE email = 'ada.role@example.test'")
                .query(String.class).single();
        assertThat(hash).startsWith("$2").doesNotContain("long-enough-1");
    }

    @Test
    void duplicateEmailIsConflictCaseInsensitive() throws Exception {
        Session s = registerReader();
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"X\",\"email\":\"%s\",\"password\":\"long-enough-1\"}"
                                .formatted(s.email().toUpperCase())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_EXISTS"));
    }

    @Test
    void validationErrorsUseStandardShape() throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"email\":\"not-an-email\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.details.length()").value(3))
                .andExpect(jsonPath("$.details[0].field").isNotEmpty());
    }

    @Test
    void malformedJsonIs400() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void loginWithWrongPasswordOrUnknownEmailIs401() throws Exception {
        Session s = registerReader();
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"wrong-password\"}".formatted(s.email())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nobody@example.test\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void loginSucceedsAndMeReturnsProfile() throws Exception {
        Session s = registerReader();
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"correct-horse-1\"}".formatted(s.email())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expiresIn").isNumber());
        mvc.perform(get("/api/users/me").header("Authorization", s.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(s.userId()))
                .andExpect(jsonPath("$.email").value(s.email()))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void protectedEndpointsRequireValidToken() throws Exception {
        mvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer not.a.jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(get("/api/bookshelf")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/recommendations/for-you")).andExpect(status().isUnauthorized());
    }

    @Test
    void profileAndGenreOnboarding() throws Exception {
        Session s = registerReader();
        mvc.perform(put("/api/users/me").header("Authorization", s.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Renamed Reader\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Renamed Reader"));
        mvc.perform(put("/api/users/me/genres").header("Authorization", s.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"genreIds\":[3,6,6]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onboardingCompleted").value(true))
                .andExpect(jsonPath("$.genres.length()").value(2));
        mvc.perform(put("/api/users/me/genres").header("Authorization", s.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"genreIds\":[99999]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        // skip = empty list
        mvc.perform(put("/api/users/me/genres").header("Authorization", s.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"genreIds\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.genres.length()").value(0))
                .andExpect(jsonPath("$.onboardingCompleted").value(true));
    }

    @Test
    void oversizedBodyIsRejected() throws Exception {
        String huge = "x".repeat(70_000);
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@b.test\",\"password\":\"" + huge + "\"}"))
                .andExpect(status().isContentTooLarge())
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
    }
}
