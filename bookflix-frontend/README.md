# Bookflix frontend

React, Vite, TypeScript, Tailwind CSS, React Router, and TanStack Query.

```sh
npm ci
npm run dev
```

Open http://localhost:5173. Live mode uses the Spring Boot API at http://localhost:8080 by default. See [the REST contract](../docs/api/contracts.md) and [backend setup](../bookflix-backend/README.md). Copy this directory's `.env.example` to `.env.local` to override `VITE_API_BASE_URL`. If the frontend origin changes, include it in the backend's CORS configuration.

## Demo mode

Set `VITE_API_MODE=demo` in `.env.local` and restart Vite when the backend is unavailable. Demo mode uses the repository's public-domain seed catalog and stores changes in the current browser. Use a fictional email and a throwaway password to register, or sign in as the demo administrator with `admin@example.test` and `bookflix-demo`. The demo uses local catalog rankings and returns an error for a model rebuild, which requires the live service. The live build uses `VITE_API_MODE=live` or no mode override.

## Checks

```sh
npm run typecheck
npm run lint
npm run build
```

For static hosting, serve `dist/` and rewrite app routes to `index.html`. Only the public API URL and mode belong in the frontend environment.
