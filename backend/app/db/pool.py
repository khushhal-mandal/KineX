"""The asyncpg connection pool.

Raw asyncpg is the runtime data path — see the locked decisions in the root design doc.
SQLAlchemy appears in this project only as alembic's engine.
"""

import asyncpg

from app.config import Settings


async def create_pool(settings: Settings) -> asyncpg.Pool:
    return await asyncpg.create_pool(
        settings.asyncpg_dsn,
        min_size=settings.db_pool_min_size,
        max_size=settings.db_pool_max_size,
    )
