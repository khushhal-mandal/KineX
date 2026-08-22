"""Session ingest and read-back.

    POST /sessions   a batch of sessions with their reps, idempotent
    GET  /sessions   this device's sessions, newest first, keyset-paginated

**`device_id` comes from the JWT and from nowhere else.** It is not a field on any model
in this file, so there is no request body that could carry one — a device cannot write to
another device's account by asking to, because there is no way to ask.

Field names on the wire are snake_case, matching the Postgres columns. See "The wire
format" in the root design doc for why that boundary sits at the client's serializer.
"""

import base64
import logging
import math
from uuid import UUID

import asyncpg
from fastapi import APIRouter, Depends, HTTPException, Query, Request, status
from pydantic import BaseModel, Field, field_validator, model_validator

from app.auth.dependencies import require_device

logger = logging.getLogger("kinex.api.sessions")

router = APIRouter(tags=["sessions"])

MAX_SESSIONS_PER_BATCH = 200
MAX_REPS_PER_SESSION = 500
MAX_PAGE_SIZE = 200

# Postgres column widths. Validated here so a client bug is a 422 naming the field rather
# than a 500 out of the driver.
_INT32 = 2**31 - 1
_INT64 = 2**63 - 1


class RepIn(BaseModel):
    rep_index: int = Field(ge=0, le=_INT32)
    peak_progress: float
    violation_mask: int = Field(ge=0, le=_INT32)

    @field_validator("peak_progress")
    @classmethod
    def finite(cls, value: float) -> float:
        # Rejecting NaN and infinity is not clamping. The value is deliberately unbounded
        # above — engine slot [6] is unclamped and a real 38.75 has been recorded — but
        # `real` will happily store a NaN that no later query can interpret.
        if not math.isfinite(value):
            raise ValueError("peak_progress must be finite")
        return value


class SessionIn(BaseModel):
    client_session_id: UUID
    exercise_id: int = Field(ge=0, le=_INT32)
    started_at_ms: int = Field(ge=0, le=_INT64)
    duration_ms: int = Field(ge=0, le=_INT64)
    rep_count: int = Field(ge=1, le=_INT32)
    reps: list[RepIn] = Field(max_length=MAX_REPS_PER_SESSION)

    @model_validator(mode="after")
    def consistent(self) -> "SessionIn":
        if self.rep_count != len(self.reps):
            raise ValueError(
                f"rep_count is {self.rep_count} but {len(self.reps)} reps were sent"
            )
        indices = [rep.rep_index for rep in self.reps]
        if len(set(indices)) != len(indices):
            # Left to ON CONFLICT this would silently drop a rep rather than fail.
            raise ValueError("rep_index values must be unique within a session")
        return self


class IngestRequest(BaseModel):
    sessions: list[SessionIn] = Field(min_length=1, max_length=MAX_SESSIONS_PER_BATCH)

    @model_validator(mode="after")
    def unique_ids(self) -> "IngestRequest":
        ids = [session.client_session_id for session in self.sessions]
        if len(set(ids)) != len(ids):
            raise ValueError("client_session_id values must be unique within a batch")
        return self


class IngestResponse(BaseModel):
    created: list[UUID]
    already_present: list[UUID]


class SessionOut(BaseModel):
    client_session_id: UUID
    exercise_id: int
    started_at_ms: int
    duration_ms: int
    rep_count: int
    received_at: str


class PageOut(BaseModel):
    sessions: list[SessionOut]
    next_cursor: str | None


def _encode_cursor(started_at_ms: int, row_id: int) -> str:
    return base64.urlsafe_b64encode(f"{started_at_ms}:{row_id}".encode()).rstrip(b"=").decode()


def _decode_cursor(cursor: str) -> tuple[int, int]:
    try:
        padded = cursor + "=" * (-len(cursor) % 4)
        started, _, row_id = base64.urlsafe_b64decode(padded).decode().partition(":")
        return int(started), int(row_id)
    except Exception:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST, detail="malformed cursor"
        )


@router.post("/sessions", response_model=IngestResponse, status_code=status.HTTP_200_OK)
async def ingest_sessions(
    body: IngestRequest,
    request: Request,
    device_id: str = Depends(require_device),
) -> IngestResponse:
    """Idempotent on `(device_id, client_session_id)`.

    Two properties this has to hold, both tested:

    - **A duplicate batch is a no-op.** `ON CONFLICT DO NOTHING` on the sessions insert,
      and the same on reps. Re-sending an identical batch writes nothing and reports
      every session as already present.
    - **A partial retry completes.** Reps are inserted for *every* session in the batch,
      not only the newly created ones. A first attempt that wrote the session row and
      then died before its reps leaves a gap that the retry fills, which is exactly the
      case a "skip anything already present" shortcut would strand forever.
    """
    sessions = body.sessions
    pool: asyncpg.Pool = request.app.state.pool

    async with pool.acquire() as connection:
        async with connection.transaction():
            created_ids = [
                record["client_session_id"]
                for record in await connection.fetch(
                    "INSERT INTO sessions"
                    " (device_id, client_session_id, exercise_id, started_at_ms,"
                    "  duration_ms, rep_count)"
                    " SELECT $1, u.client_session_id, u.exercise_id, u.started_at_ms,"
                    "        u.duration_ms, u.rep_count"
                    " FROM unnest($2::uuid[], $3::int[], $4::bigint[], $5::bigint[],"
                    "             $6::int[])"
                    "   AS u(client_session_id, exercise_id, started_at_ms, duration_ms,"
                    "        rep_count)"
                    " ON CONFLICT (device_id, client_session_id) DO NOTHING"
                    " RETURNING client_session_id",
                    device_id,
                    [s.client_session_id for s in sessions],
                    [s.exercise_id for s in sessions],
                    [s.started_at_ms for s in sessions],
                    [s.duration_ms for s in sessions],
                    [s.rep_count for s in sessions],
                )
            ]

            # Server ids for the whole batch, created and pre-existing alike. Scoped by
            # device_id, so this cannot see another device's rows even if a client_session_id
            # collided across two devices — which is legal and expected.
            id_by_client_id = {
                record["client_session_id"]: record["id"]
                for record in await connection.fetch(
                    "SELECT id, client_session_id FROM sessions"
                    " WHERE device_id = $1 AND client_session_id = ANY($2::uuid[])",
                    device_id,
                    [s.client_session_id for s in sessions],
                )
            }

            session_ids: list[int] = []
            rep_indices: list[int] = []
            peaks: list[float] = []
            masks: list[int] = []
            for session in sessions:
                server_id = id_by_client_id[session.client_session_id]
                for rep in session.reps:
                    session_ids.append(server_id)
                    rep_indices.append(rep.rep_index)
                    peaks.append(rep.peak_progress)
                    masks.append(rep.violation_mask)

            if session_ids:
                await connection.execute(
                    "INSERT INTO reps (session_id, rep_index, peak_progress, violation_mask)"
                    " SELECT * FROM unnest($1::bigint[], $2::int[], $3::real[], $4::int[])"
                    " ON CONFLICT (session_id, rep_index) DO NOTHING",
                    session_ids,
                    rep_indices,
                    peaks,
                    masks,
                )

    created = set(created_ids)
    # `created_count`, not `created`: LogRecord already has a `created` attribute (the
    # record's own timestamp) and stdlib logging raises KeyError rather than shadowing
    # it. Any reserved LogRecord name in an `extra` is a 500 on the happy path.
    logger.info(
        "sessions ingested",
        extra={
            "device_id": device_id,
            "created_count": len(created),
            "already_present_count": len(sessions) - len(created),
        },
    )
    return IngestResponse(
        created=[s.client_session_id for s in sessions if s.client_session_id in created],
        already_present=[
            s.client_session_id for s in sessions if s.client_session_id not in created
        ],
    )


@router.get("/sessions", response_model=PageOut)
async def list_sessions(
    request: Request,
    device_id: str = Depends(require_device),
    limit: int = Query(default=50, ge=1, le=MAX_PAGE_SIZE),
    cursor: str | None = Query(default=None),
) -> PageOut:
    """This device's sessions, newest first.

    Keyset rather than OFFSET. Sessions are ordered by `started_at_ms DESC` and a sync
    inserts at the front, so an OFFSET page walked while a sync is running would skip
    rows — the bug would look like missing history rather than like pagination.

    Reps are not included. A page of sessions with every rep inline is a large response
    for a screen that only needs counts, and nothing needs the reps back yet: the device
    is still the source of truth. Restoring a history onto a new device is Phase 9 and
    will want its own endpoint.
    """
    keyset = _decode_cursor(cursor) if cursor else None
    pool: asyncpg.Pool = request.app.state.pool

    async with pool.acquire() as connection:
        rows = await connection.fetch(
            "SELECT id, client_session_id, exercise_id, started_at_ms, duration_ms,"
            "       rep_count, received_at"
            " FROM sessions"
            " WHERE device_id = $1"
            "   AND ($2::bigint IS NULL OR (started_at_ms, id) < ($2::bigint, $3::bigint))"
            " ORDER BY started_at_ms DESC, id DESC"
            " LIMIT $4",
            device_id,
            keyset[0] if keyset else None,
            keyset[1] if keyset else None,
            limit + 1,  # one extra, purely to learn whether a next page exists
        )

    has_more = len(rows) > limit
    page = rows[:limit]
    return PageOut(
        sessions=[
            SessionOut(
                client_session_id=row["client_session_id"],
                exercise_id=row["exercise_id"],
                started_at_ms=row["started_at_ms"],
                duration_ms=row["duration_ms"],
                rep_count=row["rep_count"],
                received_at=row["received_at"].isoformat(),
            )
            for row in page
        ],
        next_cursor=(
            _encode_cursor(page[-1]["started_at_ms"], page[-1]["id"])
            if has_more and page
            else None
        ),
    )
