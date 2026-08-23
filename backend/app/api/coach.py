"""POST /coach/chat — hybrid retrieval, then a hosted model.

    question -> embed (local) -> pgvector search + SQL facts -> prompt -> Groq -> reply

**Authenticated, unconditionally.** This is the opposite of `POST /crashes`, and the
contrast is worth holding onto: a crash report is anonymous because a 401 would destroy
the report you most want. Here, `device_id` from the JWT is the *only* thing that decides
whose training gets read, so an unauthenticated request has nothing to answer about. It
is also the one endpoint that spends a shared third-party quota.

`device_id` comes from the token and from nowhere else, as everywhere in this API. There
is no field on `ChatRequest` that could carry one.
"""

import logging
import time

import asyncpg
from fastapi import APIRouter, Depends, HTTPException, Request, status
from pydantic import BaseModel, Field

from app.auth.dependencies import require_device
from app.coach.prompts import CHAT_SYSTEM, chat_prompt
from app.coach.retrieval import search_summaries, training_facts
from app.config import Settings, get_settings
from app.llm import ChatProvider, EmbeddingProvider, ProviderError
from app.llm import get_chat_provider, get_embedding_provider

logger = logging.getLogger("kinex.api.coach")

router = APIRouter(tags=["coach"])

MAX_QUESTION_CHARS = 1000
SUMMARIES_RETRIEVED = 4

# Same fixed-window shape as the crash limiter, keyed by device instead of by address,
# and with the same two caveats: it resets with the process and multiplies by replica
# count. Duplicated rather than extracted — the two differ in key, limit and window, and
# factoring them together would mean editing a tested, working endpoint for no behaviour
# change. This one exists because /coach/chat is the only thing in the system that spends
# a finite third-party free-tier quota, and one device should not be able to spend all of it.
_hits: dict[str, tuple[float, int]] = {}


def _rate_limit(device_id: str, settings: Settings) -> None:
    now = time.monotonic()
    window = settings.chat_rate_limit_window_seconds

    started, count = _hits.get(device_id, (now, 0))
    if now - started >= window:
        started, count = now, 0

    if count >= settings.chat_rate_limit_requests:
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail="too many coach requests",
            headers={"Retry-After": str(int(window - (now - started)))},
        )

    _hits[device_id] = (started, count + 1)


class ChatRequest(BaseModel):
    # No conversation history. Each request retrieves fresh against the full question,
    # which is what makes the grounding correct; carrying turns would mean deciding what
    # to re-retrieve on, and nothing has asked for a follow-up yet.
    message: str = Field(min_length=1, max_length=MAX_QUESTION_CHARS)


class GroundingOut(BaseModel):
    """What the answer was built from.

    Returned because "why did it say that" is otherwise unanswerable from outside the
    process — the reply is prose and the retrieval that produced it is invisible. It is
    also how a retrieval bug is told apart from a model bug.
    """

    sessions_considered: int
    exercises: list[str]
    summaries: list["SummaryRefOut"]


class SummaryRefOut(BaseModel):
    period_start_ms: int
    period_end_ms: int
    session_count: int
    distance: float


class ChatResponse(BaseModel):
    reply: str
    model: str
    grounding: GroundingOut


@router.post("/coach/chat", response_model=ChatResponse)
async def coach_chat(
    body: ChatRequest,
    request: Request,
    device_id: str = Depends(require_device),
    settings: Settings = Depends(get_settings),
    chat: ChatProvider = Depends(get_chat_provider),
    embedder: EmbeddingProvider = Depends(get_embedding_provider),
) -> ChatResponse:
    _rate_limit(device_id, settings)

    question = body.message.strip()
    if not question:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT, detail="empty message"
        )

    pool: asyncpg.Pool = request.app.state.pool
    now_ms = int(time.time() * 1000)

    # Embedding first, and locally: ~0.13s warm, which is affordable inside a request in
    # a way that generation on the same box would not be. See app/llm/ollama.py.
    try:
        query_vector = await embedder.embed_query(question)
    except ProviderError as exc:
        # A degraded answer beats no answer. Losing the vector half costs the narrative
        # background; the SQL facts — every number the reply is allowed to state — are
        # still there, so the coach can still answer correctly, just with less colour.
        logger.warning(
            "coach embedding unavailable, falling back to facts only",
            extra={"device_id": device_id, "error": str(exc)},
        )
        query_vector = None

    async with pool.acquire() as connection:
        facts = await training_facts(connection, device_id, now_ms=now_ms)
        summaries = (
            await search_summaries(
                connection, device_id, query_vector, limit=SUMMARIES_RETRIEVED
            )
            if query_vector is not None
            else []
        )

    if not facts["totals"]["sessions"]:
        # Nothing to ground an answer in. Answered here rather than spent on a model that
        # would have to be trusted not to invent a history — and it would sometimes.
        return ChatResponse(
            reply=(
                "I don't have any workouts for you yet. Record a set in the app and it"
                " will sync here — once there's a session or two I can tell you how"
                " you're tracking."
            ),
            model="none",
            grounding=GroundingOut(sessions_considered=0, exercises=[], summaries=[]),
        )

    try:
        reply = await chat.complete(
            CHAT_SYSTEM, chat_prompt(facts, summaries, question)
        )
    except ProviderError as exc:
        logger.error(
            "coach chat failed", extra={"device_id": device_id, "error": str(exc)}
        )
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="the coach is unavailable right now",
        )

    logger.info(
        "coach replied",
        extra={
            "device_id": device_id,
            "model": chat.name,
            "summaries_retrieved": len(summaries),
            "sessions_considered": facts["totals"]["sessions"],
            "question_chars": len(question),
        },
    )

    return ChatResponse(
        reply=reply,
        model=chat.name,
        grounding=GroundingOut(
            sessions_considered=facts["totals"]["sessions"],
            exercises=[row["exercise"] for row in facts["by_exercise"]],
            summaries=[
                SummaryRefOut(
                    period_start_ms=row["period_start_ms"],
                    period_end_ms=row["period_end_ms"],
                    session_count=row["session_count"],
                    distance=row["distance"],
                )
                for row in summaries
            ],
        ),
    )
