from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Request, status
from fastapi.concurrency import run_in_threadpool

from app.core.model_store import ModelStore, RebuildInProgressError
from app.core.security import require_internal_token
from app.recommenders import scoring
from app.schemas import (ForYouRequest, HealthResponse, RebuildRequest, RebuildResponse, RecommendationResponse,
                         SimilarRequest)
from app.training.builder import EmptyCatalogError, ModelArtifacts

router = APIRouter()
internal = APIRouter(prefix="/internal", dependencies=[Depends(require_internal_token)])


def _store(request: Request) -> ModelStore:
    return request.app.state.store


def _model(request: Request) -> ModelArtifacts:
    model = _store(request).current()
    if model is None:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE,
                            detail={"code": "MODEL_NOT_READY", "message": "no model has been built yet"})
    return model


@router.get("/health", response_model=HealthResponse)
def health(request: Request) -> HealthResponse:
    model = _store(request).current()
    if model is None:
        return HealthResponse(status="ok", model_loaded=False)
    return HealthResponse(status="ok", model_loaded=True, model_version=model.model_version,
                          built_at=model.built_at, book_count=model.book_count,
                          catalog_fingerprint=model.catalog_fingerprint)


@internal.post("/recommendations/for-you", response_model=RecommendationResponse)
def recommend_for_you(req: ForYouRequest, request: Request) -> RecommendationResponse:
    model = _model(request)
    items, strategy = scoring.for_you(model, req)
    return RecommendationResponse(items=items, strategy=strategy, model_version=model.model_version)


@internal.post("/recommendations/similar", response_model=RecommendationResponse)
def recommend_similar(req: SimilarRequest, request: Request) -> RecommendationResponse:
    model = _model(request)
    try:
        items, strategy = scoring.similar(model, req)
    except scoring.UnknownBookError:
        raise HTTPException(status.HTTP_404_NOT_FOUND,
                            detail={"code": "UNKNOWN_BOOK", "message": "book is not in the current model"})
    return RecommendationResponse(items=items, strategy=strategy, model_version=model.model_version)


@internal.post("/model/rebuild", response_model=RebuildResponse)
async def rebuild(req: RebuildRequest, request: Request) -> RebuildResponse:
    store = _store(request)
    try:
        model = await run_in_threadpool(store.rebuild, req)
    except RebuildInProgressError:
        raise HTTPException(status.HTTP_409_CONFLICT,
                            detail={"code": "REBUILD_IN_PROGRESS", "message": "a rebuild is already running"})
    except EmptyCatalogError:
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_CONTENT,
                            detail={"code": "EMPTY_CATALOG", "message": "cannot build a model from an empty catalog"})
    return RebuildResponse(model_version=model.model_version, book_count=model.book_count, built_at=model.built_at,
                           catalog_fingerprint=model.catalog_fingerprint, evaluation=model.evaluation)
