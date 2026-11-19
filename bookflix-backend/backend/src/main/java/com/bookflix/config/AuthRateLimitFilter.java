package com.bookflix.config;

import java.io.IOException;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.bookflix.common.ErrorCode;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * In-memory sliding-window rate limit for login/register, keyed by client IP.
 * Single-instance only; put a shared limiter (gateway/Redis) in front when scaling out.
 * The remote address is used as-is: X-Forwarded-For is not trusted unless a proxy is configured
 * (server.forward-headers-strategy).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class AuthRateLimitFilter extends OncePerRequestFilter {

    private static final int MAX_TRACKED_CLIENTS = 50_000;

    private final int maxRequests;
    private final long windowMillis;
    private final Clock clock;
    private final JsonErrorWriter errorWriter;
    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public AuthRateLimitFilter(BookflixProperties props, JsonErrorWriter errorWriter) {
        this(props.security().authRateLimitMaxRequests(), props.security().authRateLimitWindow().toMillis(),
                Clock.systemUTC(), errorWriter);
    }

    AuthRateLimitFilter(int maxRequests, long windowMillis, Clock clock, JsonErrorWriter errorWriter) {
        this.maxRequests = maxRequests;
        this.windowMillis = windowMillis;
        this.clock = clock;
        this.errorWriter = errorWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !"POST".equalsIgnoreCase(request.getMethod())
                || !(uri.equals("/api/auth/login") || uri.equals("/api/auth/register"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!tryAcquire(request.getRemoteAddr())) {
            response.setHeader("Retry-After", String.valueOf(Math.max(1, windowMillis / 1000)));
            errorWriter.write(request, response, ErrorCode.RATE_LIMITED,
                    "Too many authentication attempts. Please wait and try again.");
            return;
        }
        chain.doFilter(request, response);
    }

    boolean tryAcquire(String clientKey) {
        long now = clock.millis();
        if (hits.size() > MAX_TRACKED_CLIENTS) {
            hits.entrySet().removeIf(e -> {
                synchronized (e.getValue()) {
                    Long last = e.getValue().peekLast();
                    return last == null || now - last > windowMillis;
                }
            });
        }
        Deque<Long> window = hits.computeIfAbsent(clientKey == null ? "unknown" : clientKey, k -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && now - window.peekFirst() >= windowMillis) {
                window.pollFirst();
            }
            if (window.size() >= maxRequests) {
                return false;
            }
            window.addLast(now);
            return true;
        }
    }
}
