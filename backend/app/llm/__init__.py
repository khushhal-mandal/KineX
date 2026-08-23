"""Provider construction, and the dependencies that hand them to a route.

Which provider serves which path is decided here and nowhere else. Retrieval and prompt
assembly never import `groq` or `ollama` — swapping Ollama in for chat is this file
returning a different object, which is what "behind a provider interface" has to mean to
be worth anything.

The `get_*` functions read from `app.state` rather than constructing anything, so the
providers are built once at startup and so that tests can replace them through FastAPI's
`dependency_overrides`. That seam is the reason the test suite never calls Groq.
"""

from fastapi import Request

from app.config import Settings
from app.llm.base import ChatProvider, EmbeddingProvider, ProviderError
from app.llm.groq import GroqChatProvider
from app.llm.http import build_client
from app.llm.ollama import OllamaChatProvider, OllamaEmbeddingProvider

__all__ = [
    "ChatProvider",
    "EmbeddingProvider",
    "ProviderError",
    "build_client",
    "build_chat_provider",
    "build_batch_chat_provider",
    "build_embedding_provider",
    "get_chat_provider",
    "get_embedding_provider",
]


def build_chat_provider(client, settings: Settings) -> ChatProvider:
    """The interactive path: hosted, because a person is waiting.

    To move interactive chat onto the local model, return `OllamaChatProvider` here.
    Nothing else in the codebase changes — and on the current hardware the result would
    be a reply that takes a couple of minutes, which is why it is not the default.
    """
    return GroqChatProvider(client, settings)


def build_batch_chat_provider(client, settings: Settings) -> ChatProvider:
    """The batch path: local, because nothing is waiting and the tokens are free.

    The root design doc locks scheduled batch work to the local 3B model. A nightly job that
    spent a hosted free-tier quota per device would run out of quota before it ran out
    of devices.
    """
    return OllamaChatProvider(client, settings)


def build_embedding_provider(client, settings: Settings) -> EmbeddingProvider:
    """Local for both paths. An embedding is one forward pass — see app/llm/ollama.py."""
    return OllamaEmbeddingProvider(client, settings)


def get_chat_provider(request: Request) -> ChatProvider:
    return request.app.state.chat_provider


def get_embedding_provider(request: Request) -> EmbeddingProvider:
    return request.app.state.embedding_provider
