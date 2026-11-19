package com.bookflix.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "bookflix")
public record BookflixProperties(Security security, Ml ml, DevAdmin devAdmin) {

    public record Security(
            String jwtSecret,
            String jwtIssuer,
            Duration jwtTtl,
            boolean requireSecrets,
            List<String> corsAllowedOrigins,
            int authRateLimitMaxRequests,
            Duration authRateLimitWindow,
            long maxRequestBytes) {
    }

    public record Ml(
            boolean enabled,
            String baseUrl,
            String internalToken,
            Duration connectTimeout,
            Duration readTimeout,
            Duration rebuildTimeout,
            Duration rebuildCheckInterval,
            Duration periodicRebuildInterval) {

        /** The ML client is only usable when enabled AND a shared internal token is configured. */
        public boolean usable() {
            return enabled && internalToken != null && !internalToken.isBlank();
        }
    }

    public record DevAdmin(boolean enabled, String email, String password, String name) {
    }
}
