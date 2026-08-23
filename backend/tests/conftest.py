"""Test fixtures.

**These tests run against a real PostgreSQL, never a mock or a substitute.** Almost
everything worth testing here is a database behaviour — `ON CONFLICT DO NOTHING`, a
unique constraint, `DELETE ... RETURNING` under concurrency, the `uuid` type folding two
spellings into one value. A fake would be a second implementation of Postgres written by
the person whose assumptions are being checked, and it would agree with them.

The real server is the one in compose. Tests create their own database inside it, migrate
it with the real alembic revisions, and truncate between tests, so the dev database is
never touched.
"""

import hashlib
import os
import struct
import subprocess
from collections.abc import AsyncIterator
from dataclasses import dataclass, field

import asyncpg
import pytest
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

from app.auth.keys import auth_message, b64u_encode

TEST_DATABASE = "kinex_test"

# Must match migration 0004's column and app/llm/ollama.py.
EMBEDDING_DIMENSIONS = 768


@dataclass
class Device:
    """A test device holding a real Ed25519 key, authenticated through the real
    handshake. Nothing here fabricates a token — if auth breaks, every test that needs a
    device fails, which is the correct blast radius."""

    private_key: Ed25519PrivateKey
    public_key: str
    device_id: str
    token: str

    @property
    def auth(self) -> dict[str, str]:
        return {"Authorization": f"Bearer {self.token}"}

    def sign(self, nonce: str) -> str:
        return b64u_encode(self.private_key.sign(auth_message(nonce)))


async def authenticate(client, private_key: Ed25519PrivateKey | None = None) -> Device:
    """Run the full two-step handshake and return the resulting device."""
    from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat

    private_key = private_key or Ed25519PrivateKey.generate()
    public_key = b64u_encode(
        private_key.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)
    )

    challenge = await client.post("/auth/challenge", json={"public_key": public_key})
    assert challenge.status_code == 200, challenge.text
    nonce = challenge.json()["nonce"]

    signature = b64u_encode(private_key.sign(auth_message(nonce)))
    token = await client.post(
        "/auth/token",
        json={"public_key": public_key, "nonce": nonce, "signature": signature},
    )
    assert token.status_code == 200, token.text
    body = token.json()
    return Device(private_key, public_key, body["device_id"], body["access_token"])


@pytest.fixture
async def device(client) -> Device:
    return await authenticate(client)


@pytest.fixture
async def other_device(client) -> Device:
    """A second, unrelated device. Used to prove one device cannot reach another's data."""
    return await authenticate(client)


def _server_dsn() -> str:
    """The compose Postgres, from KINEX_DATABASE_URL, with the database name stripped."""
    dsn = os.environ["KINEX_DATABASE_URL"]
    return dsn.rsplit("/", 1)[0]


def _test_dsn() -> str:
    return f"{_server_dsn()}/{TEST_DATABASE}"


@pytest.fixture(scope="session", autouse=True)
def migrated_database() -> None:
    """Drop, create and migrate the test database once per run.

    Dropped first rather than reused so a half-finished previous run cannot make this one
    pass. Migrated by shelling out to the real `alembic upgrade head` — the same command
    the migrate container runs — rather than by creating tables from a fixture, because a
    schema built by the tests would not be the schema the app ships.
    """
    import asyncio

    async def rebuild() -> None:
        connection = await asyncpg.connect(f"{_server_dsn()}/postgres")
        try:
            await connection.execute(
                f'DROP DATABASE IF EXISTS "{TEST_DATABASE}" WITH (FORCE)'
            )
            await connection.execute(f'CREATE DATABASE "{TEST_DATABASE}"')
        finally:
            await connection.close()

    asyncio.run(rebuild())

    result = subprocess.run(
        ["alembic", "upgrade", "head"],
        cwd="/srv",
        env={**os.environ, "KINEX_DATABASE_URL": _test_dsn()},
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        raise RuntimeError(f"alembic failed:\n{result.stdout}\n{result.stderr}")


@pytest.fixture(autouse=True)
async def clean_tables(migrated_database: None) -> AsyncIterator[None]:
    """Empty every table before each test.

    TRUNCATE rather than DELETE so identity sequences restart, and CASCADE so the
    ordering of these table names never has to track the foreign keys between them.
    """
    connection = await asyncpg.connect(_test_dsn())
    try:
        await connection.execute(
            "TRUNCATE devices, auth_challenges, crashes, sessions, reps,"
            " session_summaries RESTART IDENTITY CASCADE"
        )
    finally:
        await connection.close()
    yield


@pytest.fixture(autouse=True)
def reset_crash_rate_limiter() -> None:
    """The crash rate limiter is a module-level dict, so without this the first test to
    post a few reports would decide how many the next one is allowed."""
    from app.api.crashes import _hits

    _hits.clear()


@pytest.fixture
async def db() -> AsyncIterator[asyncpg.Connection]:
    """A direct connection, for asserting on what actually landed in the tables."""
    connection = await asyncpg.connect(_test_dsn())
    try:
        yield connection
    finally:
        await connection.close()


# --- The LLM seam ---------------------------------------------------------------------
#
# No test in this suite reaches Groq or Ollama, and that is not squeamishness about
# mocking: a suite whose result depends on a third-party free tier goes red for reasons
# that have nothing to do with this code, and a suite that burns quota cannot be run in a
# loop. What is faked is exactly the network boundary — the provider interface — and
# everything below it stays real. pgvector still computes the distances, the real SQL
# still runs against the real Postgres, and the prompt is assembled by the real code.
#
# The recording provider is the point. It captures what was *sent to the model*, so the
# isolation test can assert on the prompt rather than on the reply. Asserting a fake's
# canned reply contains no leaked data would prove nothing at all.


@dataclass
class RecordingChatProvider:
    """Implements ChatProvider, answers with a canned string, remembers every prompt."""

    reply: str = "You are training consistently. Keep the squat depth where it is."
    calls: list[tuple[str, str]] = field(default_factory=list)
    fail_with: Exception | None = None

    @property
    def name(self) -> str:
        return "fake-chat"

    async def complete(self, system: str, user: str) -> str:
        self.calls.append((system, user))
        if self.fail_with:
            raise self.fail_with
        return self.reply

    @property
    def last_prompt(self) -> str:
        """System and user turns together — everything the model was shown."""
        assert self.calls, "the chat provider was never called"
        return "\n".join(self.calls[-1])


@dataclass
class FakeEmbeddingProvider:
    """Implements EmbeddingProvider with deterministic unit vectors.

    Derived from a hash of the text, so the same text always embeds identically and a
    test can plant a vector it knows. Semantic similarity is not modelled and is not
    needed: what these tests check is that retrieval is *scoped*, ordered and stored
    correctly, none of which depends on the vectors meaning anything.
    """

    documents: list[str] = field(default_factory=list)
    queries: list[str] = field(default_factory=list)

    @property
    def name(self) -> str:
        return "fake-embed"

    @property
    def dimensions(self) -> int:
        return EMBEDDING_DIMENSIONS

    async def embed_documents(self, texts: list[str]) -> list[list[float]]:
        self.documents.extend(texts)
        return [fake_vector(text) for text in texts]

    async def embed_query(self, text: str) -> list[float]:
        self.queries.append(text)
        return fake_vector(text)


def fake_vector(text: str) -> list[float]:
    """A deterministic unit vector for `text`, 768 wide."""
    # 4 bytes per dimension, 32 bytes per sha256 block.
    blocks = EMBEDDING_DIMENSIONS * 4 // hashlib.sha256().digest_size
    raw = b"".join(
        hashlib.sha256(f"{text}:{block}".encode()).digest() for block in range(blocks)
    )
    values = [
        struct.unpack_from("<i", raw, offset)[0] / 2**31
        for offset in range(0, EMBEDDING_DIMENSIONS * 4, 4)
    ]
    norm = sum(value * value for value in values) ** 0.5
    return [value / norm for value in values]


@pytest.fixture
def chat_provider() -> RecordingChatProvider:
    return RecordingChatProvider()


@pytest.fixture
def embedding_provider() -> FakeEmbeddingProvider:
    return FakeEmbeddingProvider()


@pytest.fixture(autouse=True)
def reset_chat_rate_limiter() -> None:
    """Per-process dict, same as the crash limiter: without this the first test to ask a
    few questions decides how many the next one is allowed."""
    from app.api.coach import _hits

    _hits.clear()


@pytest.fixture
async def client(
    migrated_database: None,
    chat_provider: RecordingChatProvider,
    embedding_provider: FakeEmbeddingProvider,
) -> AsyncIterator["object"]:
    """The real FastAPI app, in-process, over ASGI, talking to the test database.

    The app is imported inside the fixture and its settings cache cleared first, so that
    `KINEX_DATABASE_URL` points at the test database before `get_settings()` ever runs.

    The two LLM providers are overridden for every test in the suite, not only the coach
    ones. Making it opt-out rather than opt-in means a new test cannot reach Groq by
    forgetting something — the failure mode of the other arrangement is a test that
    passes locally and spends real quota.
    """
    os.environ["KINEX_DATABASE_URL"] = _test_dsn()

    from httpx import ASGITransport, AsyncClient

    from app.config import get_settings

    get_settings.cache_clear()

    from app.llm import get_chat_provider, get_embedding_provider
    from app.main import app

    app.dependency_overrides[get_chat_provider] = lambda: chat_provider
    app.dependency_overrides[get_embedding_provider] = lambda: embedding_provider

    try:
        async with AsyncClient(
            transport=ASGITransport(app=app), base_url="http://test"
        ) as http_client:
            # Enter the app's lifespan so app.state.pool exists and points at the test DB.
            async with app.router.lifespan_context(app):
                yield http_client
    finally:
        # `app` is module-level and outlives this fixture, so an override left behind
        # would silently apply to every later test.
        app.dependency_overrides.clear()
