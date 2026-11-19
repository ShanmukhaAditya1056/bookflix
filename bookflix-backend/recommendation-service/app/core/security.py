"""Shared-secret guard for /internal/* endpoints. Only the Spring backend knows the token."""
from __future__ import annotations

import hmac

from fastapi import Header, HTTPException, Request, status


def require_internal_token(request: Request, x_internal_token: str | None = Header(default=None)) -> None:
    settings = request.app.state.settings
    if not settings.token_configured:
        # Fail closed: never serve internal endpoints without a configured secret.
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE,
                            detail={"code": "NOT_CONFIGURED", "message": "internal token not configured"})
    if not x_internal_token or not hmac.compare_digest(x_internal_token.encode(), settings.internal_token.encode()):
        raise HTTPException(status.HTTP_401_UNAUTHORIZED,
                            detail={"code": "UNAUTHORIZED", "message": "invalid internal token"})
