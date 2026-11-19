# Bookflix frontend

React, Vite, TypeScript, Tailwind CSS, React Router, and TanStack Query.

```sh
npm ci
npm run dev
```

Open http://localhost:5173. The frontend uses the live Spring Boot API at http://localhost:8080 by default. Copy `.env.example` to `.env.local` and set `VITE_API_BASE_URL` if Spring Boot runs on another port. If the frontend origin changes, include it in the backend's CORS configuration. Start the backend using the [backend setup](../bookflix-backend/README.md) and refer to the [REST contract](../docs/api/contracts.md) for expected responses.

The frontend keeps the bearer token in session storage for the configured API URL and asks the user to sign in again on expiry or an authenticated `401`. If the recommendation service is unavailable, the API can return catalog-based picks and their reasons. If the API itself is unreachable, the frontend shows connection and retry states.

## Optional development demo

Run `npm run dev:demo` to try the public-domain seed catalog without a backend. This mode stores activity in the current browser. Use a fictional email and throwaway password to register, or sign in as the demo administrator with `admin@example.test` and `bookflix-demo`. Production builds always use the live API.

## Checks

```sh
npm run typecheck
npm run lint
npm test
npm run build
```

For static hosting, serve `dist/` and rewrite app routes to `index.html`. Configure the public API URL at build time.
