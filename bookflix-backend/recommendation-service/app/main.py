"""Bookflix recommendation service (private; reachable only from the Spring backend network)."""
from __future__ import annotations

import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.api.routes import internal, router
from app.core.config import Settings, load_settings
from app.core.model_store import ModelStore

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
log = logging.getLogger("bookflix.ml")


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or load_settings()

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        if not settings.token_configured:
            log.warning("BOOKFLIX_ML_INTERNAL_TOKEN is not set: /internal endpoints will refuse all requests")
        app.state.store.load_latest()
        yield

    app = FastAPI(title="Bookflix Recommendation Service", version="1.0.0", lifespan=lifespan,
                  docs_url=None, redoc_url=None, openapi_url=None)
    app.state.settings = settings
    app.state.store = ModelStore(settings.model_dir, settings.keep_versions)
    app.include_router(router)
    app.include_router(internal)
    return app


app = create_app()
