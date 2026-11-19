package com.bookflix.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import tools.jackson.databind.json.JsonMapper;

class AuthRateLimitFilterTest {

    private final AtomicLong now = new AtomicLong(1_000_000);
    private final Clock clock = new Clock() {
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(now.get()); }
    };
    private final AuthRateLimitFilter filter =
            new AuthRateLimitFilter(3, 60_000, clock, new JsonErrorWriter(JsonMapper.builder().findAndAddModules().build()));

    @Test
    void blocksAfterLimitAndRecoversAfterWindow() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertThat(call("10.0.0.1").getStatus()).isEqualTo(200);
        }
        MockHttpServletResponse blocked = call("10.0.0.1");
        assertThat(blocked.getStatus()).isEqualTo(429);
        assertThat(blocked.getContentAsString()).contains("RATE_LIMITED");
        assertThat(blocked.getHeader("Retry-After")).isEqualTo("60");

        assertThat(call("10.0.0.2").getStatus()).isEqualTo(200); // other clients unaffected

        now.addAndGet(60_001);
        assertThat(call("10.0.0.1").getStatus()).isEqualTo(200);
    }

    @Test
    void onlyAppliesToLoginAndRegister() throws Exception {
        for (int i = 0; i < 10; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/books");
            req.setRemoteAddr("10.0.0.3");
            MockHttpServletResponse res = new MockHttpServletResponse();
            filter.doFilter(req, res, new MockFilterChain());
            assertThat(res.getStatus()).isEqualTo(200);
        }
    }

    private MockHttpServletResponse call(String ip) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/auth/login");
        req.setRemoteAddr(ip);
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());
        return res;
    }
}
