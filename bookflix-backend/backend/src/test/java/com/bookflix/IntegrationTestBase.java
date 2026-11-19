package com.bookflix;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.mysql.MySQLContainer;

import com.bookflix.users.Role;
import com.bookflix.users.User;
import com.bookflix.users.UserRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Boots the full application against a real MySQL 8.4 (Testcontainers) with Flyway migrations + seed.
 * One container is shared by all integration test classes.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    static {
        MYSQL.start();
    }

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected ObjectMapper json;
    @Autowired
    protected UserRepository users;
    @Autowired
    protected PasswordEncoder passwordEncoder;
    @Autowired
    protected JdbcClient jdbc;

    protected record Session(long userId, String token, String email) {
        public String bearer() {
            return "Bearer " + token;
        }
    }

    protected Session registerReader() throws Exception {
        String email = "reader-" + UUID.randomUUID() + "@example.test";
        String body = """
                {"name":"Test Reader","email":"%s","password":"correct-horse-1"}""".formatted(email);
        String res = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        JsonNode node = json.readTree(res);
        return new Session(node.at("/user/id").asLong(), node.get("accessToken").asString(), email);
    }

    protected Session createAdmin() throws Exception {
        String email = "admin-" + UUID.randomUUID() + "@example.test";
        users.save(new User("Test Admin", email, passwordEncoder.encode("admin-pass-123"), Role.ADMIN));
        String res = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"admin-pass-123\"}".formatted(email)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode node = json.readTree(res);
        return new Session(node.at("/user/id").asLong(), node.get("accessToken").asString(), email);
    }

    protected JsonNode body(org.springframework.test.web.servlet.ResultActions actions) throws Exception {
        return json.readTree(actions.andReturn().getResponse().getContentAsString());
    }
}
