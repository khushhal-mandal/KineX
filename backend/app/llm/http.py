"""The shared HTTP client and the one retry policy both providers use.

One `AsyncClient` for the process, built at startup and closed on shutdown, because a
client per request throws away the connection pool and pays a fresh TLS handshake to Groq
every time — on 2 OCPU that handshake is not free.

Timeouts are always explicit and always split. httpx's default is 5 seconds flat, which
is simultaneously too short for CPU inference and too long to hold a user's request open
against a provider that has stopped answering. Connect and read want opposite values: a
refused connection is instant and worth retrying, a slow read is the model working.
"""

import asyncio
import logging
import random

import httpx

from app.config import Settings
from app.llm.base import ProviderError

logger = logging.getLogger("kinex.llm")

# 429 and 5xx only. A 4xx is a wrong request and will be exactly as wrong the fourth
# time — retrying it burns quota to reproduce a bug. 408 and 425 are in because they are
# the server saying "again", not "no".
RETRYABLE_STATUS = frozenset({408, 425, 429, 500, 502, 503, 504})


def build_client(settings: Settings) -> httpx.AsyncClient:
    """The process's HTTP client. Timeouts are per-call, so none is set here.

    `limits` is small on purpose: this box has 2 cores and two upstreams, and a large
    pool would only queue more work against a model that can serve one thing at a time.
    """
    return httpx.AsyncClient(
        limits=httpx.Limits(max_connections=8, max_keepalive_connections=4),
        follow_redirects=False,
    )


def timeout(settings: Settings, read_seconds: float) -> httpx.Timeout:
    return httpx.Timeout(
        connect=settings.llm_connect_timeout_seconds,
        read=read_seconds,
        write=settings.llm_connect_timeout_seconds,
        pool=settings.llm_connect_timeout_seconds,
    )


def _delay(settings: Settings, attempt: int, retry_after: str | None) -> float:
    """Exponential backoff with jitter, unless the server named a delay itself.

    Groq sends `retry-after` on a 429 and it is better information than any local guess.
    It is capped anyway — a provider asking for a five-minute wait is a provider this
    request is not going to be answered by, and the caller should hear that now.
    """
    if retry_after:
        try:
            return min(float(retry_after), settings.llm_backoff_max_seconds)
        except ValueError:
            pass  # HTTP-date form; fall through to the computed backoff.
    base = min(
        settings.llm_backoff_base_seconds * (2**attempt), settings.llm_backoff_max_seconds
    )
    # Jitter, so that several devices rate-limited by the same window do not all come
    # back at the same instant and rate-limit each other again.
    return base * (0.5 + random.random() * 0.5)


async def post_json(
    client: httpx.AsyncClient,
    url: str,
    *,
    payload: dict,
    headers: dict[str, str],
    read_timeout: float,
    settings: Settings,
    label: str,
) -> dict:
    """POST JSON, retrying only what is worth retrying, and raise ProviderError otherwise.

    Every exit from this function is either a parsed JSON object or a ProviderError. No
    caller has to look at a status code, and no caller gets an httpx exception it would
    have to know httpx to handle.
    """
    last: str = "no attempt was made"

    for attempt in range(settings.llm_max_retries + 1):
        try:
            response = await client.post(
                url,
                json=payload,
                headers=headers,
                timeout=timeout(settings, read_timeout),
            )
        except httpx.TimeoutException as exc:
            # A timeout is retryable but usually will not help: if the model needed
            # longer than the read budget once, it will need longer again. Retried
            # anyway because a single slow response can also be a cold model load.
            last = f"{label} timed out after {read_timeout}s ({type(exc).__name__})"
        except httpx.TransportError as exc:
            # Connection refused, DNS failure, connection reset. Transient by nature —
            # on this deployment it is usually the ollama container still starting.
            last = f"{label} transport error: {type(exc).__name__}: {exc}"
        else:
            if response.status_code not in RETRYABLE_STATUS:
                if response.is_success:
                    try:
                        return response.json()
                    except ValueError as exc:
                        raise ProviderError(
                            f"{label} returned a non-JSON body: {exc}"
                        ) from exc
                # A 4xx that is not 408/425/429: wrong model name, bad key, malformed
                # request. Terminal, and the body says which — truncated because a
                # provider error page can be a whole HTML document.
                raise ProviderError(
                    f"{label} refused the request: HTTP {response.status_code}"
                    f" {response.text[:300]}",
                    retryable=False,
                )
            last = f"{label} returned HTTP {response.status_code}"
            if attempt < settings.llm_max_retries:
                await asyncio.sleep(
                    _delay(settings, attempt, response.headers.get("retry-after"))
                )
                logger.warning(
                    "llm retry",
                    extra={"provider": label, "attempt": attempt + 1, "reason": last},
                )
                continue
            break

        if attempt < settings.llm_max_retries:
            await asyncio.sleep(_delay(settings, attempt, None))
            logger.warning(
                "llm retry",
                extra={"provider": label, "attempt": attempt + 1, "reason": last},
            )

    raise ProviderError(
        f"{last} (gave up after {settings.llm_max_retries + 1} attempts)", retryable=True
    )
