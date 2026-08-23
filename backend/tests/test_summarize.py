"""The batch summary job.

Runs the real `summarize_device` against the real database with fake providers, so the
SQL, the upsert and the period derivation are all genuine and only the two model calls
are not.
"""

import asyncpg
import pytest

from app.jobs.summarize import summarize_device
from app.llm.base import ProviderError
from tests.test_coach import BASE_MS, DAY_MS, a_session


async def run_job(db, device_id, chat, embedder, **kw):
    return await summarize_device(
        db,
        device_id,
        chat,
        embedder,
        now_ms=kw.pop("now_ms", BASE_MS + DAY_MS),
        window_days=kw.pop("window_days", 30),
        force=kw.pop("force", False),
        dry_run=kw.pop("dry_run", False),
    )


async def test_a_summary_is_written_with_its_facts_and_its_embedding(
    client, device, db, chat_provider, embedding_provider
):
    await client.post(
        "/sessions",
        json={
            "sessions": [
                a_session(reps=5, started_at_ms=BASE_MS),
                a_session(reps=7, started_at_ms=BASE_MS + DAY_MS // 2),
            ]
        },
        headers=device.auth,
    )

    outcome = await run_job(db, device.device_id, chat_provider, embedding_provider)
    assert outcome.startswith("written")

    row = await db.fetchrow(
        "SELECT device_id, period_start_ms, period_end_ms, session_count, summary,"
        " facts, embedding_model, chat_model,"
        # The vector is never selected as a vector anywhere in the app; cast to text
        # here purely so the test can prove it is 768 wide and not null.
        " vector_dims(embedding) AS dims"
        " FROM session_summaries"
    )

    assert row["device_id"] == device.device_id
    assert row["period_start_ms"] == BASE_MS
    assert row["period_end_ms"] == BASE_MS + DAY_MS // 2
    assert row["session_count"] == 2
    assert row["summary"] == chat_provider.reply
    assert row["dims"] == 768
    assert row["embedding_model"] == "fake-embed"
    assert row["chat_model"] == "fake-chat"

    import json

    facts = json.loads(row["facts"])
    assert facts["totals"]["reps"] == 12


async def test_the_prompt_the_model_gets_carries_the_real_numbers(
    client, device, db, chat_provider, embedding_provider
):
    await client.post(
        "/sessions", json={"sessions": [a_session(reps=11)]}, headers=device.auth
    )

    await run_job(db, device.device_id, chat_provider, embedding_provider)

    prompt = chat_provider.last_prompt
    assert "11 reps" in prompt
    assert "Squat" in prompt


async def test_unassessed_exercises_reach_the_batch_prompt_as_volume_only(
    client, device, db, chat_provider, embedding_provider
):
    """The structural fix for a fabrication a prompt rule could not stop.

    On 22 Aug 2026 `qwen2.5:3b` was told not to comment on the form of unchecked
    exercises, was handed a "form not checked" label, and wrote *"The Push-ups have
    remained at a consistent depth of 1.00, with no form issues noted"* — a technique
    claim about an exercise no camera assessed. Depth and form figures are now removed
    before the prompt exists, so there is nothing to paraphrase. Volume survives, because
    reps are counted for every exercise and that count is true either way.
    """
    await client.post(
        "/sessions",
        json={
            "sessions": [
                a_session(reps=6, exercise_id=0),  # squat: assessed
                a_session(reps=9, exercise_id=1),  # push-up: not
            ]
        },
        headers=device.auth,
    )

    await run_job(db, device.device_id, chat_provider, embedding_provider)

    prompt = chat_provider.last_prompt
    squat_line = next(line for line in prompt.splitlines() if "Squat:" in line)
    pushup_line = next(line for line in prompt.splitlines() if "Push-up:" in line)

    # The push-up still appears, and its volume is intact.
    assert "9 reps" in pushup_line
    assert "3 sessions" not in pushup_line  # sanity: one session, not a miscount
    # But nothing about how well it was done — not a depth, not a count, not the label.
    assert "depth" not in pushup_line
    assert "form" not in pushup_line
    assert "flagged" not in pushup_line

    # The assessed exercise is untouched, so this is not passing by stripping everything.
    assert "typical depth" in squat_line
    assert "form flagged on" in squat_line

    # The depth trend covers assessed exercises only.
    trend = [line for line in prompt.splitlines() if line.startswith("  Push-up:")]
    assert all("->" not in line for line in trend)


async def test_every_aggregate_rep_count_says_total(
    client, device, db, chat_provider, embedding_provider
):
    """Rendering fix for a misread, not a fabrication — the figures were correct.

    `4 sessions, 8 reps` was read by `qwen2.5:3b` as *"four sessions, each including
    eight repetitions"*, turning 8 reps into 32. Any rep count that aggregates more than
    one session now says so on the same line as the number, before anything can be
    inferred about which it is.
    """
    await client.post(
        "/sessions",
        json={
            "sessions": [
                a_session(reps=4, exercise_id=0, started_at_ms=BASE_MS),
                a_session(reps=4, exercise_id=0, started_at_ms=BASE_MS + 1000),
            ]
        },
        headers=device.auth,
    )

    await run_job(db, device.device_id, chat_provider, embedding_provider)
    prompt = chat_provider.last_prompt

    all_time = next(line for line in prompt.splitlines() if line.startswith("All time:"))
    squat = next(line for line in prompt.splitlines() if "Squat:" in line)
    # Matched on the ISO-week shape rather than a hardcoded year, which would rot.
    weekly = next(line for line in prompt.splitlines() if "-W" in line and "reps" in line)

    assert "8 reps total" in all_time and "2 sessions" in all_time
    assert "8 reps total" in squat and "2 sessions" in squat
    assert "8 reps total" in weekly

    # A single session's own line is not an aggregate and must NOT say total, or the word
    # stops meaning anything where it does appear.
    per_session = next(line for line in prompt.splitlines() if line.startswith("  on "))
    assert "4 reps" in per_session
    assert "total" not in per_session


async def test_a_single_session_is_not_pluralised(
    client, device, db, chat_provider, embedding_provider
):
    await client.post(
        "/sessions", json={"sessions": [a_session(reps=5)]}, headers=device.auth
    )

    await run_job(db, device.device_id, chat_provider, embedding_provider)
    squat = next(
        line for line in chat_provider.last_prompt.splitlines() if "Squat:" in line
    )

    assert "1 session," in squat
    assert "1 sessions" not in squat


async def test_the_stored_facts_match_what_the_model_was_given(
    client, device, db, chat_provider, embedding_provider
):
    """`facts` is the audit trail — it has to equal the prompt's figures, not the query's.

    Storing the full query while prompting with less would make the column describe a
    summary that was never generated from it, which is worse than storing nothing: it
    would read as evidence.
    """
    await client.post(
        "/sessions",
        json={
            "sessions": [
                a_session(reps=6, exercise_id=0),
                a_session(reps=9, exercise_id=1),
            ]
        },
        headers=device.auth,
    )

    await run_job(db, device.device_id, chat_provider, embedding_provider)

    import json

    facts = json.loads(await db.fetchval("SELECT facts FROM session_summaries"))
    by_id = {row["exercise_id"]: row for row in facts["by_exercise"]}

    assert by_id[1]["reps"] == 9  # volume kept
    for field in ("median_peak_progress", "reps_with_violations", "implausible_reps", "form_checked"):
        assert field not in by_id[1], f"{field} survived into the stored facts"
    # The assessed exercise keeps all of them.
    for field in ("median_peak_progress", "reps_with_violations", "form_checked"):
        assert field in by_id[0]
    assert [row["exercise_id"] for row in facts["depth_trend"]] == [0]


async def test_the_chat_path_still_labels_unchecked_exercises(
    client, device, chat_provider
):
    """The strip is batch-only, deliberately. "Your push-ups haven't been checked for
    form" is a useful and correct answer to a question about push-up form, and the hosted
    model gives it reliably where the local 3B one does not."""
    await client.post(
        "/sessions",
        json={"sessions": [a_session(reps=4, exercise_id=1)]},
        headers=device.auth,
    )

    await client.post(
        "/coach/chat", json={"message": "how is my form?"}, headers=device.auth
    )

    pushup_line = next(
        line for line in chat_provider.last_prompt.splitlines() if "Push-up:" in line
    )
    assert "form not checked" in pushup_line


async def test_the_summary_is_embedded_as_a_document_not_as_a_query(
    client, device, db, chat_provider, embedding_provider
):
    await client.post(
        "/sessions", json={"sessions": [a_session()]}, headers=device.auth
    )

    await run_job(db, device.device_id, chat_provider, embedding_provider)

    assert embedding_provider.documents == [chat_provider.reply]
    assert embedding_provider.queries == []


async def test_rerunning_over_unchanged_sessions_writes_nothing_new(
    client, device, db, chat_provider, embedding_provider
):
    """The period is derived from the sessions read, not from the clock, so an unchanged
    history is recognised as unchanged. Without the skip, an idle device costs a minute
    of CPU inference every night to regenerate the same paragraph."""
    await client.post(
        "/sessions", json={"sessions": [a_session()]}, headers=device.auth
    )

    assert (await run_job(db, device.device_id, chat_provider, embedding_provider)).startswith(
        "written"
    )
    assert await run_job(db, device.device_id, chat_provider, embedding_provider) == "unchanged"

    assert await db.fetchval("SELECT count(*) FROM session_summaries") == 1
    assert len(chat_provider.calls) == 1  # the second run did not reach the model


async def test_force_regenerates_in_place_rather_than_appending(
    client, device, db, chat_provider, embedding_provider
):
    """A summary is derived, not recorded — unlike a session, a better prompt should be
    able to replace one. So this upserts where POST /sessions does nothing."""
    await client.post(
        "/sessions", json={"sessions": [a_session()]}, headers=device.auth
    )
    await run_job(db, device.device_id, chat_provider, embedding_provider)

    chat_provider.reply = "A second, better paragraph."
    assert (
        await run_job(db, device.device_id, chat_provider, embedding_provider, force=True)
    ).startswith("written")

    assert await db.fetchval("SELECT count(*) FROM session_summaries") == 1
    assert (
        await db.fetchval("SELECT summary FROM session_summaries")
        == "A second, better paragraph."
    )


async def test_a_new_session_produces_a_new_row_not_an_overwrite(
    client, device, db, chat_provider, embedding_provider
):
    """A different window is a different summary. The upsert key is the period, so
    training again tomorrow does not silently replace yesterday's entry."""
    await client.post(
        "/sessions",
        json={"sessions": [a_session(started_at_ms=BASE_MS)]},
        headers=device.auth,
    )
    await run_job(db, device.device_id, chat_provider, embedding_provider)

    await client.post(
        "/sessions",
        json={"sessions": [a_session(started_at_ms=BASE_MS + DAY_MS)]},
        headers=device.auth,
    )
    await run_job(
        db,
        device.device_id,
        chat_provider,
        embedding_provider,
        now_ms=BASE_MS + 2 * DAY_MS,
    )

    assert await db.fetchval("SELECT count(*) FROM session_summaries") == 2


async def test_a_device_with_no_sessions_is_skipped_without_calling_a_model(
    client, device, db, chat_provider, embedding_provider
):
    assert await run_job(db, device.device_id, chat_provider, embedding_provider) == "no sessions"
    assert chat_provider.calls == []
    assert await db.fetchval("SELECT count(*) FROM session_summaries") == 0


async def test_nothing_is_stored_when_generation_fails(
    client, device, db, chat_provider, embedding_provider
):
    """A row whose summary failed would be a row that cannot be regenerated — the period
    would exist and the skip would consider it done."""
    await client.post(
        "/sessions", json={"sessions": [a_session()]}, headers=device.auth
    )
    chat_provider.fail_with = ProviderError("ollama timed out", retryable=True)

    with pytest.raises(ProviderError):
        await run_job(db, device.device_id, chat_provider, embedding_provider)

    assert await db.fetchval("SELECT count(*) FROM session_summaries") == 0


async def test_a_dry_run_reads_and_generates_but_writes_nothing(
    client, device, db, chat_provider, embedding_provider
):
    await client.post(
        "/sessions", json={"sessions": [a_session()]}, headers=device.auth
    )

    outcome = await run_job(
        db, device.device_id, chat_provider, embedding_provider, dry_run=True
    )

    assert outcome.startswith("dry-run")
    assert len(chat_provider.calls) == 1
    assert await db.fetchval("SELECT count(*) FROM session_summaries") == 0


async def test_the_job_only_reads_the_device_it_was_given(
    client, device, other_device, db, chat_provider, embedding_provider
):
    await client.post(
        "/sessions", json={"sessions": [a_session(reps=4)]}, headers=device.auth
    )
    await client.post(
        "/sessions",
        json={"sessions": [a_session(reps=88, exercise_id=8)]},
        headers=other_device.auth,
    )

    await run_job(db, device.device_id, chat_provider, embedding_provider)

    prompt = chat_provider.last_prompt
    assert "Jumping jack" not in prompt
    assert "88" not in prompt
    assert await db.fetchval(
        "SELECT count(*) FROM session_summaries WHERE device_id = $1", device.device_id
    ) == 1
