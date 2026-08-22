"""The ACRA endpoint.

**This is the only unauthenticated write in the API, and that is deliberate — do not
"fix" it by requiring a token.** ACRA fires when the app is already broken. A crash in
the auth path, or before the device has ever registered, is precisely the report worth
having, and a 401 would throw it away. A bad, expired or absent token all behave the
same: the report lands, unattributed.

Because it is anonymous, the abuse surface is closed at the two places that matter:

- the body is capped and the cap is enforced while reading the stream, before anything
  parses it, so an oversized report costs a few kilobytes rather than the whole body;
- requests are rate-limited per source address.

The payload is stored whole, as an opaque JSONB document. It is deliberately not split
into columns: ACRA's report shape varies by version and configuration, and a parser that
guessed wrong would silently drop the reports it failed to understand.
"""

import json
import logging
import time

import asyncpg
from fastapi import APIRouter, Depends, HTTPException, Request, Response, status

from app.auth.dependencies import optional_device
from app.config import Settings, get_settings

logger = logging.getLogger("kinex.api.crashes")

router = APIRouter(tags=["crashes"])

# Fixed-window counter, per process, in memory. Crude on purpose — the alternative is a
# table written on every anonymous request, which is the thing being rate-limited. Two
# consequences to know rather than discover: it resets when the pod restarts, and with
# more than one replica the effective limit multiplies by the replica count.
_hits: dict[str, tuple[float, int]] = {}


def _client_address(request: Request) -> str:
    # Traefik terminates TLS in Phase 7 and every request will then arrive from the
    # ingress. X-Forwarded-For is only trustworthy behind a proxy that overwrites it —
    # until that is configured, the direct peer is the honest answer.
    return request.client.host if request.client else "unknown"


def _rate_limit(request: Request, settings: Settings) -> None:
    address = _client_address(request)
    now = time.monotonic()
    window = settings.crash_rate_limit_window_seconds

    started, count = _hits.get(address, (now, 0))
    if now - started >= window:
        started, count = now, 0

    if count >= settings.crash_rate_limit_requests:
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail="too many crash reports",
            headers={"Retry-After": str(int(window - (now - started)))},
        )

    _hits[address] = (started, count + 1)

    # Bounded cleanup: without it this dict is a slow leak keyed by attacker-chosen
    # addresses. Only ever touched on this endpoint, which is already rate-limited.
    if len(_hits) > 10_000:
        for key, (key_started, _) in list(_hits.items()):
            if now - key_started >= window:
                _hits.pop(key, None)


async def _read_capped_body(request: Request, limit: int) -> bytes:
    """Read at most `limit` bytes, refusing anything longer.

    Content-Length is checked first because it lets an oversized request be refused
    without reading it at all — but it is a claim, not a fact, so the stream is also
    counted as it arrives. Chunked bodies have no Content-Length and only the second
    check catches those.
    """
    declared = request.headers.get("content-length")
    if declared is not None:
        try:
            if int(declared) > limit:
                raise HTTPException(
                    status_code=status.HTTP_413_CONTENT_TOO_LARGE,
                    detail=f"crash report exceeds {limit} bytes",
                )
        except ValueError:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST, detail="bad content-length"
            )

    body = bytearray()
    async for chunk in request.stream():
        body.extend(chunk)
        if len(body) > limit:
            raise HTTPException(
                status_code=status.HTTP_413_CONTENT_TOO_LARGE,
                detail=f"crash report exceeds {limit} bytes",
            )
    return bytes(body)


@router.post("/crashes", status_code=status.HTTP_202_ACCEPTED)
async def receive_crash(
    request: Request,
    device_id: str | None = Depends(optional_device),
    settings: Settings = Depends(get_settings),
) -> Response:
    _rate_limit(request, settings)

    raw = await _read_capped_body(request, settings.crash_max_body_bytes)
    if not raw:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST, detail="empty crash report"
        )

    try:
        parsed = json.loads(raw)
        # A JSON scalar is valid JSON but not a report; wrap it so the column always
        # holds an object and queries do not have to defend against a bare string.
        payload = parsed if isinstance(parsed, dict) else {"_raw": parsed}
    except (json.JSONDecodeError, UnicodeDecodeError):
        # Never reject a report for being malformed. Whatever arrived is kept verbatim,
        # because an unparseable crash report is still evidence of a crash.
        payload = {"_raw": raw.decode("utf-8", errors="replace")}

    pool: asyncpg.Pool = request.app.state.pool
    async with pool.acquire() as connection:
        await connection.execute(
            "INSERT INTO crashes (device_id, payload) VALUES ($1, $2::jsonb)",
            device_id,
            json.dumps(payload),
        )

    logger.info(
        "crash report stored",
        extra={"device_id": device_id, "bytes": len(raw), "attributed": device_id is not None},
    )
    return Response(status_code=status.HTTP_202_ACCEPTED)
