"""The provider layer, against a mock transport rather than a mock provider.

These are the only tests that exercise `app/llm/` itself, and they use httpx's
`MockTransport` so that the real provider code — payload construction, response parsing,
the retry policy, the dimension check — all runs. Only the socket is replaced.

That is a different thing from the fake providers in conftest, and both are needed: the
fakes let the API tests avoid the network, but they also mean nothing else would ever
execute a line of `groq.py`. These cover the failures that were measured against the live
APIs on 22 Aug 2026 and would otherwise ship.
"""

import httpx
import pytest

from app.config import Settings
from app.llm.base import ProviderError
from app.llm.groq import GroqChatProvider
from app.llm.ollama import OllamaEmbeddingProvider


def settings_for_tests(**overrides) -> Settings:
    base = {
        "database_url": "postgresql://x:x@localhost:5432/x",
        "jwt_secret": "test",
        "groq_api_key": "test-key",
        # Backoff to zero: these tests assert retry *counts*, and waiting through a real
        # exponential backoff would add seconds to the suite to prove nothing extra.
        "llm_backoff_base_seconds": 0.0,
        "llm_backoff_max_seconds": 0.0,
    }
    base.update(overrides)
    return Settings(**base)


def client_returning(*responses: httpx.Response) -> tuple[httpx.AsyncClient, list]:
    """A client that plays back the given responses in order, recording each request."""
    seen: list[httpx.Request] = []
    queue = list(responses)

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        return queue.pop(0) if len(queue) > 1 else queue[0]

    return httpx.AsyncClient(transport=httpx.MockTransport(handler)), seen


def groq_reply(content, finish_reason="stop", reasoning="") -> httpx.Response:
    return httpx.Response(
        200,
        json={
            "choices": [
                {
                    "message": {"role": "assistant", "content": content, "reasoning": reasoning},
                    "finish_reason": finish_reason,
                }
            ],
            "usage": {"prompt_tokens": 100, "completion_tokens": 20},
        },
    )


# --- Groq -------------------------------------------------------------------------------


async def test_an_empty_groq_reply_is_an_error_not_an_empty_string():
    """Measured against the live API on 22 Aug 2026: gpt-oss is a reasoning model, and
    with a tight budget it spends the whole thing on a hidden `reasoning` field and
    returns HTTP 200 with `content` empty and finish_reason=length. Returning that
    straight through reaches a user as a coach that said nothing at all."""
    client, _ = client_returning(
        groq_reply("", finish_reason="length", reasoning="thinking about squats...")
    )
    provider = GroqChatProvider(client, settings_for_tests())

    with pytest.raises(ProviderError, match="empty reply"):
        await provider.complete("system", "user")

    await client.aclose()


async def test_groq_sends_the_reasoning_effort_that_keeps_the_answer_non_empty():
    client, seen = client_returning(groq_reply("Nice work."))
    provider = GroqChatProvider(client, settings_for_tests())

    assert await provider.complete("system", "user") == "Nice work."

    import json

    body = json.loads(seen[0].content)
    assert body["reasoning_effort"] == "low"
    assert body["model"] == "openai/gpt-oss-20b"
    assert seen[0].headers["authorization"] == "Bearer test-key"

    await client.aclose()


async def test_a_rate_limit_is_retried_and_then_succeeds():
    """Groq's free tier rate-limits, and it is the failure that will actually fire."""
    client, seen = client_returning(
        httpx.Response(429, headers={"retry-after": "0"}), groq_reply("Recovered.")
    )
    provider = GroqChatProvider(client, settings_for_tests())

    assert await provider.complete("system", "user") == "Recovered."
    assert len(seen) == 2

    await client.aclose()


async def test_a_bad_request_is_not_retried():
    """A 4xx will be exactly as wrong the fourth time. Retrying it burns quota to
    reproduce a bug — and on a free tier, quota is the thing there is least of."""
    client, seen = client_returning(
        httpx.Response(400, json={"error": {"message": "model not found"}})
    )
    provider = GroqChatProvider(client, settings_for_tests())

    with pytest.raises(ProviderError, match="refused the request"):
        await provider.complete("system", "user")
    assert len(seen) == 1

    await client.aclose()


async def test_persistent_rate_limiting_gives_up_after_the_configured_retries():
    client, seen = client_returning(httpx.Response(429, headers={"retry-after": "0"}))
    provider = GroqChatProvider(client, settings_for_tests(llm_max_retries=2))

    with pytest.raises(ProviderError) as raised:
        await provider.complete("system", "user")

    assert raised.value.retryable is True
    assert len(seen) == 3  # the first attempt plus two retries

    await client.aclose()


# --- Ollama embeddings ------------------------------------------------------------------


def embed_reply(vectors) -> httpx.Response:
    return httpx.Response(200, json={"embeddings": vectors})


async def test_documents_and_queries_get_different_task_prefixes():
    """nomic-embed-text is asymmetric: it is trained with `search_document:` on stored
    text and `search_query:` on questions, and Ollama adds neither. Using one prefix for
    both raises nothing, logs nothing and simply retrieves worse — which is exactly the
    kind of defect that survives for months."""
    import json

    client, seen = client_returning(embed_reply([[0.1] * 768]))
    provider = OllamaEmbeddingProvider(client, settings_for_tests())

    await provider.embed_documents(["squats went well"])
    await provider.embed_query("how are my squats")

    document_input = json.loads(seen[0].content)["input"]
    query_input = json.loads(seen[1].content)["input"]

    assert document_input == ["search_document: squats went well"]
    assert query_input == ["search_query: how are my squats"]

    await client.aclose()


async def test_a_wrong_width_embedding_is_refused_at_the_provider():
    """The column is vector(768). A model swap that changes the width has to fail here,
    naming the cause, rather than as a Postgres type error three frames away — or, far
    worse, as vectors that write fine and retrieve meaninglessly."""
    client, _ = client_returning(embed_reply([[0.1] * 384]))
    provider = OllamaEmbeddingProvider(client, settings_for_tests())

    with pytest.raises(ProviderError, match="384 dimensions"):
        await provider.embed_documents(["anything"])

    await client.aclose()


async def test_a_truncated_batch_of_embeddings_is_refused():
    """Two inputs, one vector back. Zipping these together would silently attach the
    wrong embedding to the second summary."""
    client, _ = client_returning(embed_reply([[0.1] * 768]))
    provider = OllamaEmbeddingProvider(client, settings_for_tests())

    with pytest.raises(ProviderError, match="expected 2 vectors"):
        await provider.embed_documents(["one", "two"])

    await client.aclose()
