"""Groq, over its OpenAI-compatible chat completions endpoint.

The interactive half. The root design doc locks chat to a free-tier hosted API because the
local `qwen2.5:3b` answers at ~2.9 tok/s, which is fine for a nightly job and unusable
for someone waiting for a reply.

Two things measured against the live API on 22 Aug 2026 rather than assumed, both of
which would otherwise have shipped as bugs:

1. **The Llama models are gone.** `llama-3.3-70b-versatile` and `llama-3.1-8b-instant`
   are not in the catalogue. The default in `Settings` came from `GET /v1/models` on the
   real key.

2. **gpt-oss is a reasoning model and the obvious read of its response returns "".**
   With a 50-token budget and no `reasoning_effort`, the entire budget went to a hidden
   `reasoning` field, `content` came back empty, and `finish_reason` was `length` — an
   HTTP 200 carrying no answer. `reasoning_effort: "low"` is therefore configuration
   this file depends on, and an empty `content` is raised rather than returned.
"""

import logging

import httpx

from app.config import Settings
from app.llm.base import ProviderError
from app.llm.http import post_json

logger = logging.getLogger("kinex.llm.groq")


class GroqChatProvider:
    """Implements ChatProvider. See app/llm/base.py."""

    def __init__(self, client: httpx.AsyncClient, settings: Settings) -> None:
        self._client = client
        self._settings = settings

    @property
    def name(self) -> str:
        return self._settings.groq_model

    async def complete(self, system: str, user: str) -> str:
        settings = self._settings
        payload = {
            "model": settings.groq_model,
            "messages": [
                {"role": "system", "content": system},
                {"role": "user", "content": user},
            ],
            "temperature": settings.groq_temperature,
            "max_completion_tokens": settings.groq_max_completion_tokens,
            # Not tuning. See the module docstring: without this the answer is empty.
            "reasoning_effort": settings.groq_reasoning_effort,
        }

        body = await post_json(
            self._client,
            f"{settings.groq_base_url}/chat/completions",
            payload=payload,
            headers={"Authorization": f"Bearer {settings.groq_api_key}"},
            read_timeout=settings.groq_timeout_seconds,
            settings=settings,
            label="groq",
        )

        try:
            choice = body["choices"][0]
            content = choice["message"].get("content") or ""
            finish = choice.get("finish_reason")
        except (KeyError, IndexError, TypeError) as exc:
            raise ProviderError(f"groq returned an unexpected shape: {exc}") from exc

        if not content.strip():
            # The failure this whole file is careful about. Reported with the finish
            # reason because that is what distinguishes "budget went to reasoning"
            # (length) from "the model declined" (stop) — different fixes.
            raise ProviderError(
                f"groq returned an empty reply (finish_reason={finish}). If this is"
                f" `length`, the token budget went to the reasoning trace.",
                retryable=False,
            )

        usage = body.get("usage") or {}
        logger.info(
            "groq completion",
            extra={
                "model": settings.groq_model,
                "prompt_tokens": usage.get("prompt_tokens"),
                "completion_tokens": usage.get("completion_tokens"),
                "finish_reason": finish,
            },
        )
        return content.strip()
