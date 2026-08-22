"""FastAPI application entry point."""

import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request, status
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from app.api import auth, crashes, health, sessions
from app.config import get_settings
from app.db.pool import create_pool
from app.logging import configure_logging

settings = get_settings()

# Runs when uvicorn imports this module, which is after uvicorn has configured its own
# logging — so this replaces it rather than being replaced by it.
configure_logging(settings.log_level)

logger = logging.getLogger("kinex.api")


@asynccontextmanager
async def lifespan(app: FastAPI):
    app.state.pool = await create_pool(settings)
    logger.info(
        "startup",
        extra={"env": settings.env, "pool_max_size": settings.db_pool_max_size},
    )
    try:
        yield
    finally:
        await app.state.pool.close()
        logger.info("shutdown")


app = FastAPI(title="KineX backend", version="0.1.0", lifespan=lifespan)


@app.exception_handler(RequestValidationError)
async def validation_error(_: Request, exc: RequestValidationError) -> JSONResponse:
    """422s that report only where and why, never the value that was rejected.

    FastAPI's default handler echoes the offending input back in the response. That is a
    500 waiting to happen and was one: `Infinity` and `NaN` reach pydantic, because
    Python's `json.loads` accepts them, but `json.dumps` refuses to emit them — so the
    default handler crashed encoding its own error body and the client got a 500 where
    a 422 was correct. Dropping `input` fixes that, and not echoing a request back to
    its sender is the better default regardless.
    """
    return JSONResponse(
        status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
        content={
            "detail": [
                {"loc": list(error["loc"]), "msg": error["msg"], "type": error["type"]}
                for error in exc.errors()
            ]
        },
    )


app.include_router(health.router)
app.include_router(auth.router)
app.include_router(sessions.router)
app.include_router(crashes.router)
