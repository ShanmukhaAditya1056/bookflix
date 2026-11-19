package com.bookflix.recommendations;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.bookflix.config.BookflixProperties;
import com.bookflix.recommendations.MlDtos.ForYouRequest;
import com.bookflix.recommendations.MlDtos.HealthResponse;
import com.bookflix.recommendations.MlDtos.MlRecommendationResponse;
import com.bookflix.recommendations.MlDtos.RebuildRequest;
import com.bookflix.recommendations.MlDtos.RebuildResponse;
import com.bookflix.recommendations.MlDtos.SimilarRequest;

/**
 * HTTP client for the private recommendation service. Every failure (disabled, timeout, connection
 * refused, non-2xx, bad payload) surfaces as {@link MlUnavailableException} so callers can fall back.
 * The internal token is sent only to the configured internal base URL and never returned to clients.
 */
@Component
public class MlClient {

    static final String TOKEN_HEADER = "X-Internal-Token";

    private final BookflixProperties.Ml cfg;
    private final RestClient fast;
    private final RestClient slow;

    public MlClient(BookflixProperties props, RestClient.Builder builder) {
        this.cfg = props.ml();
        this.fast = build(builder.clone(), cfg.connectTimeout(), cfg.readTimeout());
        this.slow = build(builder.clone(), cfg.connectTimeout(), cfg.rebuildTimeout());
    }

    private RestClient build(RestClient.Builder builder, Duration connect, Duration read) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(connect).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(read);
        RestClient.Builder b = builder.requestFactory(factory).baseUrl(cfg.baseUrl());
        if (cfg.usable()) {
            b = b.defaultHeader(TOKEN_HEADER, cfg.internalToken());
        }
        return b.build();
    }

    public boolean usable() {
        return cfg.usable();
    }

    public HealthResponse health() {
        ensureUsable();
        try {
            return fast.get().uri("/health").retrieve().body(HealthResponse.class);
        } catch (RestClientException e) {
            throw new MlUnavailableException("health check failed", e);
        }
    }

    public MlRecommendationResponse forYou(ForYouRequest req) {
        return post(fast, "/internal/recommendations/for-you", req, MlRecommendationResponse.class);
    }

    public MlRecommendationResponse similar(SimilarRequest req) {
        return post(fast, "/internal/recommendations/similar", req, MlRecommendationResponse.class);
    }

    public RebuildResponse rebuild(RebuildRequest req) {
        return post(slow, "/internal/model/rebuild", req, RebuildResponse.class);
    }

    private <T> T post(RestClient client, String path, Object body, Class<T> type) {
        ensureUsable();
        try {
            T result = client.post().uri(path).contentType(MediaType.APPLICATION_JSON).body(body)
                    .retrieve().body(type);
            if (result == null) {
                throw new MlUnavailableException("empty response from " + path, null);
            }
            return result;
        } catch (HttpClientErrorException e) {
            throw new MlUnavailableException(path + " returned " + e.getStatusCode().value(), e, e.getStatusCode());
        } catch (RestClientException e) {
            throw new MlUnavailableException(path + " failed: " + e.getClass().getSimpleName(), e);
        }
    }

    private void ensureUsable() {
        if (!cfg.usable()) {
            throw new MlUnavailableException("recommendation service disabled or not configured", null);
        }
    }

    public static class MlUnavailableException extends RuntimeException {
        private final HttpStatusCode status;

        public MlUnavailableException(String message, Throwable cause) {
            this(message, cause, null);
        }

        public MlUnavailableException(String message, Throwable cause, HttpStatusCode status) {
            super(message, cause);
            this.status = status;
        }

        public HttpStatusCode status() {
            return status;
        }
    }
}
