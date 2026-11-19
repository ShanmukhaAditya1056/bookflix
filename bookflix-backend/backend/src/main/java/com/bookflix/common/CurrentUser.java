package com.bookflix.common;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Resolves the caller's identity strictly from the verified JWT principal.
 * User ids are never accepted from request parameters or bodies.
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static long id(Jwt jwt) {
        if (jwt == null) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "Authentication required");
        }
        try {
            return Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException e) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "Invalid token subject");
        }
    }

    /** Returns the current user id, or null for anonymous callers on public endpoints. */
    public static Long idOrNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken token) {
            return id(token.getToken());
        }
        return null;
    }
}
