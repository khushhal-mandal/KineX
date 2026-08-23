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

    # Also no default, and for the same shape of reason as the two above: a chat request
    # that discovers the key is missing has already cost a retrieval round trip and a
    # user's question. Missing configuration is an operator error and belongs at startup.
    groq_api_key: str

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

    # --- The coaching pipeline -------------------------------------------------------
    #
    # Two LLM paths, deliberately different. Batch narrative generation runs on the local
    # Ollama where latency does not matter; interactive chat runs on a hosted API because
    # 2.9 tok/s is unusable for someone waiting. Both are behind app/llm/base.py.

    groq_base_url: str = "https://api.groq.com/openai/v1"

    # Verified against this key's live /v1/models on 22 Aug 2026, not recalled — and the
    # check mattered: `llama-3.3-70b-versatile` and `llama-3.1-8b-instant` are no longer
    # in the catalogue at all, so the obvious default would have 404'd on first use.
    #
    # `openai/gpt-oss-120b` is the drop-in upgrade. `qwen/qwen3.6-27b` is not: it writes a
    # raw <think> block into `content` and would need stripping. The `groq/compound*`
    # entries are refused on purpose — they can reach for web search, and a coach that
    # answers from the open web instead of from this device's sessions is precisely the
    # failure the retrieval half of this pipeline exists to prevent.
    groq_model: str = "openai/gpt-oss-20b"

    # Load-bearing, not tuning. gpt-oss is a reasoning model: without this it spends the
    # budget on a hidden `reasoning` trace and returns `content` EMPTY with
    # finish_reason=length. Measured 22 Aug 2026 — 282 chars of reasoning, 0 of answer.
    groq_reasoning_effort: str = "low"
    groq_max_completion_tokens: int = 700
    groq_temperature: float = 0.3

    # Short. A hosted provider that has stopped answering must not hold a request open —
    # the device is waiting on this one.
    groq_timeout_seconds: float = 30.0

    # Generous, and for the opposite reason: this is CPU inference on 2 ARM cores at a
    # few tokens per second, in a nightly job nobody is waiting on.
    ollama_chat_model: str = "qwen2.5:3b"
    ollama_chat_timeout_seconds: float = 600.0

    # See "Embeddings" in the root design doc for why this model. The dimension is NOT a setting:
    # it is `vector(768)` in migration 0004 and changing the model is a re-embed and a
    # migration, not an environment variable. app/llm/ollama.py asserts the width it gets.
    embedding_model: str = "nomic-embed-text"
    ollama_embed_timeout_seconds: float = 120.0

    # Connect is separate from read everywhere: a refused connection is instant and worth
    # retrying, a slow read is the model working and is not.
    llm_connect_timeout_seconds: float = 10.0

    # Retries are for 429 and 5xx only — never for a 4xx, which will fail identically
    # however many times it is sent. Groq's free tier rate-limits, and that is the case
    # that actually fires.
    llm_max_retries: int = 3
    llm_backoff_base_seconds: float = 0.5
    llm_backoff_max_seconds: float = 8.0

    # The chat endpoint is the only thing in the system that spends a shared, exhaustible
    # third-party quota, so it is metered per device. Same crude fixed-window shape as
    # crashes, and the same two caveats — per process, and multiplied by replica count.
    chat_rate_limit_requests: int = 20
    chat_rate_limit_window_seconds: int = 3600

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
