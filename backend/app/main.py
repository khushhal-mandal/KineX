"""FastAPI application entry point."""

import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.api import health
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
app.include_router(health.router)
