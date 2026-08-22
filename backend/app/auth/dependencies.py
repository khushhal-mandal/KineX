"""Request dependencies that turn an Authorization header into a device_id.

Two of them, and the difference is the whole point:

- `require_device` — 401 on anything wrong. Every authenticated route uses this.
- `optional_device` — never raises. Only `POST /crashes` uses it, because a crash report
  arrives when the app is already broken and a 401 would throw away the report you most
  want to read.

Neither reads a device_id from a request body. There is no code path anywhere that does.
"""

from fastapi import Depends, HTTPException, Request, status

from app.auth.tokens import device_id_from_token
from app.config import Settings, get_settings

_UNAUTHENTICATED = HTTPException(
    status_code=status.HTTP_401_UNAUTHORIZED,
    detail="valid bearer token required",
    headers={"WWW-Authenticate": "Bearer"},
)


def _bearer_token(request: Request) -> str | None:
    header = request.headers.get("authorization")
    if not header:
        return None
    scheme, _, token = header.partition(" ")
    if scheme.lower() != "bearer" or not token.strip():
        return None
    return token.strip()


def optional_device(
    request: Request, settings: Settings = Depends(get_settings)
) -> str | None:
    """A missing, malformed, expired or forged token all resolve to None, identically.
    The caller cannot tell them apart, and does not need to."""
    token = _bearer_token(request)
    if token is None:
        return None
    return device_id_from_token(token, settings)


def require_device(
    request: Request, settings: Settings = Depends(get_settings)
) -> str:
    device_id = optional_device(request, settings)
    if device_id is None:
        raise _UNAUTHENTICATED
    return device_id
