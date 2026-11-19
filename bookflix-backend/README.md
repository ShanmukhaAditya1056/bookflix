# Bookflix backend & recommendation engine

- `backend/`: Spring Boot 4.1 (Java 21) REST API, with Flyway migrations and seed under `src/main/resources/db/`.
- `recommendation-service/`: FastAPI + scikit-learn TF-IDF recommender (private service).
- `database/seed/`: `catalog.json` (95 public-domain demo books) and `generate_seed_sql.py`, which writes
  `backend/src/main/resources/db/seed/R__seed_catalog.sql`.
- `scripts/smoke_e2e.py`: end-to-end smoke test against a running stack.

Docs live in [`../docs`](../docs). Start with [`docs/api/contracts.md`](../docs/api/contracts.md).

## Run everything (Docker)
```
cp .env.example .env        # at repo root; replace every placeholder
docker compose up --build   # API on http://localhost:${BOOKFLIX_API_PORT:-8080}, Swagger at /swagger-ui.html
```

## Run locally without Docker for the apps
```
docker compose up -d mysql                                   # or any MySQL 8.4 with a "bookflix" database
cd bookflix-backend/recommendation-service
python -m venv .venv && .venv/Scripts/pip install -r requirements-dev.txt
BOOKFLIX_ML_INTERNAL_TOKEN=<token> .venv/Scripts/uvicorn app.main:app --port 8000
cd ../backend
BOOKFLIX_DB_PASSWORD=<pw> BOOKFLIX_JWT_SECRET=<32+ chars> BOOKFLIX_ML_INTERNAL_TOKEN=<token> ./mvnw spring-boot:run
```
If no ML token is set, the API still works and serves SQL-ranked recommendations with `degraded: true`.

## Changing the seed catalog
Edit `database/seed/catalog.json`, then run `python bookflix-backend/database/seed/generate_seed_sql.py`.
The seed is an idempotent repeatable Flyway migration (`INSERT IGNORE` with explicit ids). It never
creates users.
