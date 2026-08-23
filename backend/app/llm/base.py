"""The provider interface: two protocols and one error.

Everything that talks to a model in this project goes through here, and the reason is
that there are two of them running different models on different hardware for different
reasons — `qwen2.5:3b` on the local CPU for nightly batch work where latency does not
matter, and a hosted API for interactive chat where 2.9 tok/s is unusable. Retrieval and
prompt assembly must not know which one they are feeding.

The protocols are deliberately narrow. `complete()` takes a system prompt and a list of
turns and returns text — no streaming, no tool calls, no function schemas, none of which
anything here needs. A wider interface would be a guess at what a second provider might
want, and the way to find that out is to add the second provider.
"""

from typing import Protocol


class ProviderError(RuntimeError):
    """A provider could not answer.

    One exception type for every failure mode — timeout, rate limit, malformed response,
    a model that returned nothing — because the caller's options are the same in all of
    them: report that the coach is unavailable. `retryable` distinguishes "the provider
    is busy" from "the request was wrong", which is the only distinction that changes
    what the HTTP layer does with it.
    """

    def __init__(self, message: str, *, retryable: bool = False) -> None:
        super().__init__(message)
        self.retryable = retryable


class ChatProvider(Protocol):
    """Text in, text out. The only thing retrieval and the batch job know about a model."""

    @property
    def name(self) -> str:
        """The model identifier, for logging and for the `chat_model` column. A stored
        summary has to say which model wrote it."""
        ...

    async def complete(self, system: str, user: str) -> str:
        """Raises ProviderError rather than returning an empty string. A model that
        answers with nothing is a failure, and it is a failure that hides: gpt-oss will
        spend its whole budget on a hidden reasoning trace and hand back `content: ""`
        with no error at all, which reaches a user as a coach that said nothing."""
        ...


class EmbeddingProvider(Protocol):
    """Text to vector.

    Separate from ChatProvider, not a method on it, because the two are separately
    swappable and in this deployment they already differ: chat is hosted, embedding is
    local. They also fail differently — a chat failure is one unanswered question, an
    embedding failure on the batch path means a summary that can never be retrieved.
    """

    @property
    def name(self) -> str:
        """Goes into the `embedding_model` column on every row. Vectors from two models
        are not comparable, so which model produced one is part of the data."""
        ...

    @property
    def dimensions(self) -> int:
        ...

    async def embed_documents(self, texts: list[str]) -> list[list[float]]:
        """For text being stored and searched later."""
        ...

    async def embed_query(self, text: str) -> list[float]:
        """For a question being matched against stored text.

        Not the same call as `embed_documents` with one element, and the difference is
        not cosmetic: asymmetric models — nomic-embed-text among them — are trained with
        a different task prefix on each side, and using one prefix for both costs
        retrieval quality with nothing anywhere reporting a problem.
        """
        ...
