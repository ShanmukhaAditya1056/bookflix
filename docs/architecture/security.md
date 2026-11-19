# Security

| Concern | Implementation |
|---|---|
| Password hashing | BCrypt, cost 12 (`SecurityConfig.passwordEncoder`). Passwords must be 8–72 characters (BCrypt's byte limit). |
| Session strategy | Stateless JWT bearer tokens (HS256, Nimbus). Claims: `sub` = user id, `role`, `iss`, `iat`, `exp`. Default TTL 2 hours. |
| CSRF | Not applicable: no cookies or sessions authenticate requests, so CSRF protection is disabled on purpose. |
| Signing secret | `BOOKFLIX_JWT_SECRET` (≥ 32 bytes). If blank and `BOOKFLIX_REQUIRE_SECRETS=false`, a random per-process key is used (dev only). Compose sets `REQUIRE_SECRETS=true`. |
| Authorization | URL rules in `SecurityConfig`: `/api/admin/**` needs `ROLE_ADMIN`; other `/api/**` need authentication except an explicit public GET list. Unmatched paths are denied. |
| Ownership | Likes, reviews, bookshelf and recommendations always use the user id from the JWT (`CurrentUser`). No endpoint accepts a user id from the client. |
| Self-registration | Always creates a `READER`; a client-supplied `role` is ignored. |
| Admin provisioning | No default credentials in source. `DevAdminSeeder` creates an admin from env vars only with the `dev` profile **and** `BOOKFLIX_DEV_ADMIN_ENABLED=true`. In production, promote a user with SQL: `UPDATE users SET role='ADMIN' WHERE email=?`. |
| Login hardening | The same error for unknown email and wrong password, with a constant-time dummy hash check. Per-IP sliding-window rate limit on login and register (default 10 per minute, then `429`). |
| CORS | Only origins in `BOOKFLIX_CORS_ALLOWED_ORIGINS`; methods GET/POST/PUT/DELETE/OPTIONS; headers Authorization, Content-Type, Accept; no credentials. |
| Request limits | Bodies over 64 KB get `413` (`RequestSizeLimitFilter`, including chunked bodies); 16 KB header limit; Bean Validation on every DTO and query parameter; page size ≤ 50. |
| Error hygiene | Consistent `ApiError` payloads with no stack traces or exception messages. Unexpected errors are logged server-side and returned as a generic `INTERNAL_ERROR`. |
| SQL injection | JPA/JDBC parameters everywhere. Sort keys come from a whitelist enum. Full-text input is tokenised to letters and digits before use. |
| ML service | No published port; reachable only on the private compose network. A shared `X-Internal-Token` is checked with `hmac.compare_digest`, and the service fails closed when unset. It receives no PII, and the token is never sent to the frontend. OpenAPI docs are disabled on the ML service. |
| Secrets | `.env.example` contains placeholders only, and `.env` is git-ignored. |
