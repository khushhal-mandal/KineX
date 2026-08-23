"""The coaching pipeline: retrieval scoping, and the JWT on /coach/chat.

The two properties the session brief named, plus the boundaries around them.

The isolation tests here assert on **the prompt that reached the model**, not on the
reply. That distinction is the whole design of this file: the provider is a fake, so its
reply is a canned string that could never contain another device's data no matter how
badly retrieval leaked. Checking the reply would be a test that passes unconditionally.
What can leak is the context, so the context is what gets searched.
"""

import json
import time
import uuid

import pytest

from app.coach.retrieval import search_summaries, training_facts
from app.llm.base import ProviderError
from tests.conftest import fake_vector

# Planted in one device's data so that a leak into another device's prompt is a substring
# search rather than a judgement call.
MARKER = "ZZZ-OTHER-DEVICE-PRIVATE-MARKER"

DAY_MS = 86_400_000

# Relative to now, deliberately, because /coach/chat reads the real clock and its fact
# queries are windowed — 90 days for the per-exercise block, 14 for the depth trend. A
# fixed epoch constant works on the day it is written and then silently falls out of
# every window, at which point these tests still pass while asserting on an empty prompt.
# Two days back puts a session inside both windows with room either side.
BASE_MS = int(time.time() * 1000) - 2 * DAY_MS


def a_session(reps: int = 3, exercise_id: int = 0, started_at_ms: int = BASE_MS, **kw):
    """A plausible set. peak_progress values are real ones from squat_8rep."""
    peaks = [1.0174, 1.0099, 1.0085, 1.0078, 1.0141, 1.0014, 1.0204, 0.9973]
    body = {
        "client_session_id": str(uuid.uuid4()),
        "exercise_id": exercise_id,
        "started_at_ms": started_at_ms,
        "duration_ms": 42000,
        "rep_count": reps,
        "reps": [
            {"rep_index": i, "peak_progress": peaks[i % len(peaks)], "violation_mask": 0}
            for i in range(reps)
        ],
    }
    body.update(kw)
    return body


async def insert_summary(db, device_id, summary, *, vector=None, start=BASE_MS, end=None):
    """Plant a summary row directly, with a vector we control.

    Written through SQL rather than by running the job, because these tests are about
    what retrieval returns, and the job is tested separately.
    """
    from app.coach.retrieval import to_pgvector

    await db.execute(
        "INSERT INTO session_summaries (device_id, period_start_ms, period_end_ms,"
        " session_count, facts, summary, embedding, embedding_model, chat_model)"
        " VALUES ($1, $2, $3, 1, '{}'::jsonb, $4, $5::vector, 'fake-embed', 'fake-chat')",
        device_id,
        start,
        end if end is not None else start,
        summary,
        to_pgvector(vector if vector is not None else fake_vector(summary)),
    )


# --- Retrieval returns only the requesting device's data -------------------------------


async def test_vector_search_returns_only_this_devices_summaries(
    client, device, other_device, db
):
    """The sharpest form of the test: both summaries carry the *same* vector.

    With identical embeddings the two rows are exactly equidistant from any query, so
    nearest-neighbour ranking cannot be what separates them — only `WHERE device_id`
    can. If that filter is dropped, the other device's row is just as near and comes
    back. A test that gave the two devices different vectors could pass by luck.
    """
    shared = fake_vector("identical")
    await insert_summary(db, device.device_id, "mine: squats went well", vector=shared)
    await insert_summary(db, other_device.device_id, f"theirs: {MARKER}", vector=shared)

    assert await db.fetchval("SELECT count(*) FROM session_summaries") == 2

    results = await search_summaries(db, device.device_id, shared, limit=10)

    assert len(results) == 1
    assert results[0]["summary"] == "mine: squats went well"
    assert MARKER not in json.dumps(results, default=str)


async def test_structured_facts_count_only_this_devices_sessions(
    client, device, other_device
):
    await client.post(
        "/sessions", json={"sessions": [a_session(reps=5, exercise_id=0)]}, headers=device.auth
    )
    await client.post(
        "/sessions",
        json={
            "sessions": [
                a_session(reps=9, exercise_id=8),
                a_session(reps=7, exercise_id=8),
            ]
        },
        headers=other_device.auth,
    )

    from app.main import app

    async with app.state.pool.acquire() as connection:
        mine = await training_facts(connection, device.device_id, now_ms=BASE_MS + 1000)
        theirs = await training_facts(
            connection, other_device.device_id, now_ms=BASE_MS + 1000
        )

    assert mine["totals"] == {
        "sessions": 1,
        "reps": 5,
        "first_session_ms": BASE_MS,
        "last_session_ms": BASE_MS,
    }
    assert theirs["totals"]["sessions"] == 2
    assert theirs["totals"]["reps"] == 16

    # `totals` and `by_exercise` come from two different queries, so asserting only on
    # totals leaves the second one's device filter unguarded — mutation testing on
    # 22 Aug 2026 removed it and this test stayed green. Every windowed block gets its
    # own assertion for that reason.
    assert [(row["exercise"], row["sessions"], row["reps"]) for row in mine["by_exercise"]] == [
        ("Squat", 1, 5)
    ]
    assert [row["exercise"] for row in theirs["by_exercise"]] == ["Jumping jack"]
    assert [row["exercise"] for row in mine["recent_sessions"]] == ["Squat"]
    assert sum(row["sessions"] for row in mine["weekly"]) == 1
    assert [row["exercise"] for row in mine["depth_trend"]] == ["Squat"]


async def test_no_other_devices_data_reaches_the_model(
    client, device, other_device, db, chat_provider
):
    """End to end, and the one that matters most.

    Everything the other device has — sessions, reps on an exercise this device never
    performed, and a summary — is planted with a marker. The assertion is that none of it
    appears in the prompt, which is the only place a retrieval leak could put it.
    """
    await client.post(
        "/sessions", json={"sessions": [a_session(reps=3, exercise_id=0)]}, headers=device.auth
    )
    # Jumping jacks, 88 reps: an exercise and a number that appear nowhere in this
    # device's own history, so either showing up in the prompt is unambiguous.
    await client.post(
        "/sessions",
        json={"sessions": [a_session(reps=88, exercise_id=8)]},
        headers=other_device.auth,
    )
    await insert_summary(db, other_device.device_id, f"their notes: {MARKER}")

    response = await client.post(
        "/coach/chat", json={"message": "how is my training going?"}, headers=device.auth
    )
    assert response.status_code == 200, response.text

    prompt = chat_provider.last_prompt
    assert MARKER not in prompt
    assert "Jumping jack" not in prompt
    assert "88" not in prompt
    # And the device's own data did make it, so this is not passing by retrieving nothing.
    assert "Squat" in prompt
    assert response.json()["grounding"]["sessions_considered"] == 1


async def test_a_devices_own_summary_does_reach_the_model(
    client, device, db, chat_provider
):
    """The other half of the isolation test. Without this, deleting all retrieval would
    make every leak test above pass."""
    await client.post(
        "/sessions", json={"sessions": [a_session(reps=3)]}, headers=device.auth
    )
    await insert_summary(db, device.device_id, "MY-OWN-SUMMARY-TEXT squats are deeper")

    response = await client.post(
        "/coach/chat", json={"message": "am i improving?"}, headers=device.auth
    )

    assert response.status_code == 200
    assert "MY-OWN-SUMMARY-TEXT" in chat_provider.last_prompt
    assert len(response.json()["grounding"]["summaries"]) == 1


# --- The chat endpoint refuses without a JWT -------------------------------------------


@pytest.mark.parametrize(
    "headers, why",
    [
        ({}, "no Authorization header at all"),
        ({"Authorization": ""}, "an empty header"),
        ({"Authorization": "Bearer"}, "the scheme with no token"),
        ({"Authorization": "Bearer "}, "the scheme with whitespace"),
        ({"Authorization": "Basic abc123"}, "the wrong scheme"),
        ({"Authorization": "abc123"}, "a bare token with no scheme"),
        ({"Authorization": "Bearer not-a-jwt"}, "a token that is not a JWT"),
        ({"Authorization": "Bearer a.b.c"}, "a JWT-shaped string that is not one"),
    ],
)
async def test_chat_refuses_without_a_valid_jwt(client, headers, why, chat_provider):
    response = await client.post(
        "/coach/chat", json={"message": "how am i doing?"}, headers=headers
    )

    assert response.status_code == 401, f"{why} should be refused"
    assert response.headers["www-authenticate"] == "Bearer"
    # The refusal has to happen before any work: an unauthenticated request that still
    # embeds a question and calls a model has spent quota on a stranger.
    assert chat_provider.calls == []


async def test_chat_refuses_a_token_signed_with_the_wrong_secret(client, chat_provider):
    """A forged token is refused for the same reason a real one is accepted — the
    signature — not because it looks wrong."""
    import jwt

    forged = jwt.encode({"sub": "someone-elses-device-id"}, "not-the-secret", "HS256")

    response = await client.post(
        "/coach/chat",
        json={"message": "how am i doing?"},
        headers={"Authorization": f"Bearer {forged}"},
    )

    assert response.status_code == 401
    assert chat_provider.calls == []


async def test_chat_refuses_an_expired_token(client, device, chat_provider):
    import jwt

    from app.config import get_settings

    expired = jwt.encode(
        {"sub": device.device_id, "exp": 1_600_000_000},
        get_settings().jwt_secret,
        "HS256",
    )

    response = await client.post(
        "/coach/chat",
        json={"message": "how am i doing?"},
        headers={"Authorization": f"Bearer {expired}"},
    )

    assert response.status_code == 401
    assert chat_provider.calls == []


async def test_chat_has_no_device_id_field_to_supply(client, device, other_device, db):
    """As everywhere in this API, device_id comes from the token and cannot be asked for.
    A body carrying one has it dropped by validation rather than honoured."""
    await client.post(
        "/sessions", json={"sessions": [a_session(reps=4)]}, headers=device.auth
    )

    response = await client.post(
        "/coach/chat",
        json={"message": "how am i doing?", "device_id": other_device.device_id},
        headers=device.auth,
    )

    assert response.status_code == 200
    assert response.json()["grounding"]["sessions_considered"] == 1


# --- Behaviour around the edges --------------------------------------------------------


async def test_a_device_with_no_sessions_is_answered_without_calling_a_model(
    client, device, chat_provider
):
    """Nothing to ground an answer in, so nothing is asked. A model handed an empty
    history will sometimes invent one, and that reads exactly like a real answer."""
    response = await client.post(
        "/coach/chat", json={"message": "how am i doing?"}, headers=device.auth
    )

    assert response.status_code == 200
    assert response.json()["model"] == "none"
    assert chat_provider.calls == []


async def test_a_provider_failure_is_a_503_not_a_500(client, device, chat_provider):
    await client.post(
        "/sessions", json={"sessions": [a_session()]}, headers=device.auth
    )
    chat_provider.fail_with = ProviderError("groq is down", retryable=True)

    response = await client.post(
        "/coach/chat", json={"message": "how am i doing?"}, headers=device.auth
    )

    assert response.status_code == 503
    assert "unavailable" in response.json()["detail"]


async def test_chat_still_answers_when_embedding_is_unavailable(
    client, device, db, chat_provider, embedding_provider
):
    """Losing the vector half degrades the answer; it must not fail the request. The SQL
    facts are where every number the reply may state comes from."""

    async def broken(text):
        raise ProviderError("ollama is not running")

    embedding_provider.embed_query = broken
    await client.post(
        "/sessions", json={"sessions": [a_session(reps=6)]}, headers=device.auth
    )
    await insert_summary(db, device.device_id, "a summary that cannot be retrieved")

    response = await client.post(
        "/coach/chat", json={"message": "how am i doing?"}, headers=device.auth
    )

    assert response.status_code == 200
    assert response.json()["grounding"]["summaries"] == []
    assert "Squat" in chat_provider.last_prompt


async def test_the_question_is_rate_limited_per_device(
    client, device, other_device, chat_provider
):
    """/coach/chat is the only endpoint that spends a shared free-tier quota."""
    from app.config import get_settings

    limit = get_settings().chat_rate_limit_requests
    await client.post(
        "/sessions", json={"sessions": [a_session()]}, headers=device.auth
    )

    for _ in range(limit):
        assert (
            await client.post(
                "/coach/chat", json={"message": "hi"}, headers=device.auth
            )
        ).status_code == 200

    refused = await client.post(
        "/coach/chat", json={"message": "hi"}, headers=device.auth
    )
    assert refused.status_code == 429
    assert "Retry-After" in refused.headers

    # Per device, not global: one device exhausting its budget must not silence another.
    await client.post(
        "/sessions", json={"sessions": [a_session()]}, headers=other_device.auth
    )
    assert (
        await client.post(
            "/coach/chat", json={"message": "hi"}, headers=other_device.auth
        )
    ).status_code == 200


async def test_an_oversized_question_is_refused(client, device, chat_provider):
    response = await client.post(
        "/coach/chat", json={"message": "x" * 5000}, headers=device.auth
    )

    assert response.status_code == 422
    assert chat_provider.calls == []


# --- What the model is told about form -------------------------------------------------


async def test_unchecked_exercises_are_labelled_as_unchecked_in_the_prompt(
    client, device, chat_provider
):
    """Nine of the ten exercises ship with violation_rules = 0, so a violation_mask of 0
    on a push-up means nothing was ever checked — not that the form was good. Without
    this label the model is handed "0 violations" and congratulates someone on technique
    no camera ever assessed, which is a fabrication that reads exactly like a fact."""
    await client.post(
        "/sessions",
        json={
            "sessions": [
                a_session(reps=4, exercise_id=0),  # squat: form is checked
                a_session(reps=4, exercise_id=1),  # push-up: it is not
            ]
        },
        headers=device.auth,
    )

    await client.post(
        "/coach/chat", json={"message": "how is my form?"}, headers=device.auth
    )

    prompt = chat_provider.last_prompt
    squat_line = next(line for line in prompt.splitlines() if "Squat:" in line)
    pushup_line = next(line for line in prompt.splitlines() if "Push-up:" in line)

    assert "form flagged on" in squat_line
    assert "form not checked" in pushup_line
