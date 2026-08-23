"""Ollama: the batch chat model, and the embeddings for both paths.

Two providers in one file because they are one server. `qwen2.5:3b` writes the nightly
narratives, `nomic-embed-text` embeds them — and also embeds the incoming question on the
interactive path, which is the one place local inference appears in a request a person is
waiting for.

**That is not an inconsistency with "chat is hosted because the local box is too slow".**
Generation is autoregressive: one forward pass per output token, which is why 3B on 2 ARM
cores comes out at ~2.9 tok/s. An embedding is a *single* forward pass over the input with
no decode loop. Measured on this stack on 22 Aug 2026: 4.1 s to load the model cold, then
**0.13 s** per query embedding warm. The two workloads are not the same shape, so the
conclusion about one does not carry to the other.

**The task prefixes are load-bearing.** nomic-embed-text is asymmetric — trained with
`search_document:` on stored text and `search_query:` on questions. Ollama does not add
them. Getting this wrong does not raise anything; it just retrieves worse, forever.
"""

import logging

import httpx

from app.config import Settings
from app.llm.base import ProviderError
from app.llm.http import post_json

logger = logging.getLogger("kinex.llm.ollama")

# Must equal the column width in migration 0004. Asserted on every call rather than
# trusted: swapping KINEX_EMBEDDING_MODEL to a 384- or 1024-dimensional model would
# otherwise fail as a Postgres error at insert time on the batch path, and — worse — as
# silently useless retrieval if it ever differed only between write and read.
EMBEDDING_DIMENSIONS = 768

DOCUMENT_PREFIX = "search_document: "
QUERY_PREFIX = "search_query: "


class OllamaChatProvider:
    """Implements ChatProvider against `/api/generate`. The batch path only."""

    def __init__(self, client: httpx.AsyncClient, settings: Settings) -> None:
        self._client = client
        self._settings = settings

    @property
    def name(self) -> str:
        return self._settings.ollama_chat_model

    async def complete(self, system: str, user: str) -> str:
        settings = self._settings
        body = await post_json(
            self._client,
            f"{settings.ollama_url}/api/generate",
            payload={
                "model": settings.ollama_chat_model,
                "system": system,
                "prompt": user,
                # Ollama streams by default, which would make the response a sequence of
                # JSON lines rather than the single object post_json returns.
                "stream": False,
                "options": {"temperature": 0.3},
            },
            headers={},
            read_timeout=settings.ollama_chat_timeout_seconds,
            settings=settings,
            label="ollama-chat",
        )

        text = (body.get("response") or "").strip()
        if not text:
            raise ProviderError(
                f"{settings.ollama_chat_model} returned an empty response"
                f" (done_reason={body.get('done_reason')})"
            )

        logger.info(
            "ollama completion",
            extra={
                "model": settings.ollama_chat_model,
                "eval_count": body.get("eval_count"),
                "total_ms": (body.get("total_duration") or 0) // 1_000_000,
            },
        )
        return text


class OllamaEmbeddingProvider:
    """Implements EmbeddingProvider against `/api/embed`.

    `/api/embed` rather than the older `/api/embeddings`: it takes a list and returns a
    list, so a batch of summaries is one request and one model load instead of N.
    """

    def __init__(self, client: httpx.AsyncClient, settings: Settings) -> None:
        self._client = client
        self._settings = settings

    @property
    def name(self) -> str:
        return self._settings.embedding_model

    @property
    def dimensions(self) -> int:
        return EMBEDDING_DIMENSIONS

    async def _embed(self, inputs: list[str]) -> list[list[float]]:
        settings = self._settings
        body = await post_json(
            self._client,
            f"{settings.ollama_url}/api/embed",
            payload={"model": settings.embedding_model, "input": inputs},
            headers={},
            read_timeout=settings.ollama_embed_timeout_seconds,
            settings=settings,
            label="ollama-embed",
        )

        vectors = body.get("embeddings")
        if not isinstance(vectors, list) or len(vectors) != len(inputs):
            raise ProviderError(
                f"{settings.embedding_model} returned {type(vectors).__name__} for"
                f" {len(inputs)} inputs; expected {len(inputs)} vectors"
            )
        for vector in vectors:
            if len(vector) != EMBEDDING_DIMENSIONS:
                # The check that turns a model swap into an immediate, named failure
                # instead of a Postgres type error three call frames away.
                raise ProviderError(
                    f"{settings.embedding_model} returned {len(vector)} dimensions but"
                    f" the embedding column is vector({EMBEDDING_DIMENSIONS}). Changing"
                    f" the embedding model requires a migration and a re-embed."
                )
        return vectors

    async def embed_documents(self, texts: list[str]) -> list[list[float]]:
        return await self._embed([DOCUMENT_PREFIX + text for text in texts])

    async def embed_query(self, text: str) -> list[float]:
        return (await self._embed([QUERY_PREFIX + text]))[0]
