"""Configuration, read from the environment.

Every setting is prefixed `KINEX_` so nothing here collides with the variables the
Postgres and Ollama images read for themselves.
"""

import os
from functools import lru_cache

from pydantic import PostgresDsn
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="KINEX_", extra="ignore")

    # No default: a missing DSN must fail at startup with the variable named, not
    # surface later as a connection to nowhere.
    database_url: PostgresDsn

    # Also no default, and for a sharper reason: a shipped default signing secret lets
    # anyone mint a token for any device.
    jwt_secret: str

    env: str = "local"
    log_level: str = "INFO"
    ollama_url: str = "http://ollama:11434"

    db_pool_min_size: int = 1
    db_pool_max_size: int = 5

    # Short, because re-authenticating costs the device two round trips and no user
    # interaction — the key is on the device, so there is nothing to prompt for.
    jwt_ttl_seconds: int = 24 * 60 * 60

    # A challenge is single-use; this only bounds how long an unconsumed one lingers.
    auth_challenge_ttl_seconds: int = 60

    # POST /crashes is the one unauthenticated write. See app/api/crashes.py.
    crash_max_body_bytes: int = 64 * 1024
    crash_rate_limit_requests: int = 30
    crash_rate_limit_window_seconds: int = 600

    @property
    def asyncpg_dsn(self) -> str:
        """The DSN as asyncpg wants it."""
        return str(self.database_url)

    @property
    def sqlalchemy_dsn(self) -> str:
        """The same DSN as SQLAlchemy wants it, for alembic's engine.

        One variable holds one DSN; the driver suffix the two libraries disagree
        about is added here rather than in a second environment variable.
        """
        return str(self.database_url).replace("postgresql://", "postgresql+asyncpg://", 1)


@lru_cache
def get_settings() -> Settings:
    return Settings()


def sqlalchemy_dsn_from_env() -> str:
    """Alembic's DSN, read without constructing `Settings`.

    Migrations deliberately do not go through `Settings`, because `Settings` requires
    `KINEX_JWT_SECRET` and a migration has no business holding a key that mints tokens.
    Whoever runs `alembic upgrade head` — the migrate container, a CI job, an operator on
    the box — needs a database URL and nothing else.
    """
    dsn = os.environ.get("KINEX_DATABASE_URL")
    if not dsn:
        raise RuntimeError("KINEX_DATABASE_URL is not set")
    return dsn.replace("postgresql://", "postgresql+asyncpg://", 1)
