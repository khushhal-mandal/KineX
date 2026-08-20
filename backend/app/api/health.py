"""Health endpoint.

One endpoint, and it reaches the database. A liveness check that only proves the Python
process is running would go green while the schema was missing or Postgres was refusing
connections, which is most of what can be wrong with this service today.

`schema_version` is alembic's current revision, read from the table alembic maintains.
It is the cheapest way to answer "did the migration actually run" from curl.
"""

import logging

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse

from app.config import get_settings

logger = logging.getLogger("kinex.api.health")

router = APIRouter()


@router.get("/health")
async def health(request: Request) -> JSONResponse:
    settings = get_settings()
    pool = request.app.state.pool

    try:
        async with pool.acquire() as connection:
            schema_version = await connection.fetchval(
                "SELECT version_num FROM alembic_version"
            )
    except Exception:
        logger.exception("health check could not reach the database")
        return JSONResponse(
            status_code=503,
            content={"status": "degraded", "env": settings.env, "database": "unreachable"},
        )

    return JSONResponse(
        content={
            "status": "ok",
            "env": settings.env,
            "database": "ok",
            "schema_version": schema_version,
        }
    )
