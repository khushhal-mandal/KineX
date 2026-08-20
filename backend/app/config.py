"""Configuration, read from the environment.

Every setting is prefixed `KINEX_` so nothing here collides with the variables the
Postgres and Ollama images read for themselves.
"""

from functools import lru_cache

from pydantic import PostgresDsn
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="KINEX_", extra="ignore")

    # No default: a missing DSN must fail at startup with the variable named, not
    # surface later as a connection to nowhere.
    database_url: PostgresDsn

    env: str = "local"
    log_level: str = "INFO"
    ollama_url: str = "http://ollama:11434"

    db_pool_min_size: int = 1
    db_pool_max_size: int = 5

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
