"""Nightly training summaries: read sessions, write a narrative, embed it, store both.

    python -m app.jobs.summarize [--device-id ID] [--window-days N] [--force] [--dry-run]

The batch half of the coaching pipeline. Everything here runs on the local model, and
nothing is waiting on it — which is the entire reason it can. Generation on 2 ARM cores
is ~3 tok/s, so a 200-word summary is a minute or two per device. That is fine at 3am and
would be indefensible in a request.

**A summary is keyed to the sessions it covers, not to the day it ran.** The period comes
from `min`/`max` of the sessions actually read, so re-running over an unchanged history
collides on `uq_session_summaries_device_period` instead of appending a near-duplicate.
The collision upserts rather than doing nothing, which is the opposite of what
`POST /sessions` does, and deliberately: a session is an immutable record of something
that happened, while a summary is derived — a better prompt or a better model should be
able to replace one in place.

Failure is per device. One device whose generation times out must not stop the other
devices' summaries from being written, so each is committed on its own and the exit code
reports whether any failed.
"""

import argparse
import asyncio
import json
import logging
import sys
import time

import asyncpg

from app.coach.prompts import SUMMARY_SYSTEM, summary_prompt
from app.coach.retrieval import DAY_MS, sessions_in_window, to_pgvector
from app.coach.retrieval import devices_with_sessions, training_facts
from app.coach.retrieval import drop_unassessed_quality_metrics
from app.config import get_settings
from app.llm import ProviderError, build_batch_chat_provider, build_client
from app.llm import build_embedding_provider
from app.logging import configure_logging

logger = logging.getLogger("kinex.jobs.summarize")


async def summarize_device(
    connection: asyncpg.Connection,
    device_id: str,
    chat,
    embedder,
    *,
    now_ms: int,
    window_days: int,
    force: bool,
    dry_run: bool,
) -> str:
    """Summarize one device. Returns what happened, for the log line and the tally."""
    since_ms = max(0, now_ms - window_days * DAY_MS)
    sessions = await sessions_in_window(connection, device_id, since_ms=since_ms)
    if not sessions:
        return "no sessions"

    period_start_ms = sessions[0]["started_at_ms"]
    period_end_ms = sessions[-1]["started_at_ms"]

    if not force:
        existing = await connection.fetchval(
            "SELECT session_count FROM session_summaries"
            " WHERE device_id = $1 AND period_start_ms = $2 AND period_end_ms = $3",
            device_id,
            period_start_ms,
            period_end_ms,
        )
        # Same window and the same number of sessions in it means nothing has changed
        # since the last run. Skipping is not an optimisation here — it is the difference
        # between a nightly job that costs minutes of CPU per idle device and one that
        # costs nothing. `--force` regenerates anyway, which is what a prompt change needs.
        if existing == len(sessions):
            return "unchanged"

    # Applied here rather than inside training_facts, so that the same dict feeds the
    # prompt and the `facts` column. Storing what was queried while prompting with less
    # would make the audit trail describe a summary that was never generated from it.
    facts = drop_unassessed_quality_metrics(
        await training_facts(
            connection, device_id, now_ms=now_ms, window_days=window_days
        )
    )

    started = time.monotonic()
    summary = await chat.complete(SUMMARY_SYSTEM, summary_prompt(facts))
    generate_seconds = time.monotonic() - started

    # Embedded after generation and in the same run, so a summary is never stored without
    # the vector that makes it findable. A row the chat path cannot retrieve is a row that
    # does not exist as far as the product is concerned.
    embedding = (await embedder.embed_documents([summary]))[0]

    if dry_run:
        print(f"--- {device_id} [{len(sessions)} sessions] ---\n{summary}\n")
        return f"dry-run ({generate_seconds:.1f}s)"

    await connection.execute(
        "INSERT INTO session_summaries"
        " (device_id, period_start_ms, period_end_ms, session_count, facts, summary,"
        "  embedding, embedding_model, chat_model)"
        " VALUES ($1, $2, $3, $4, $5::jsonb, $6, $7::vector, $8, $9)"
        " ON CONFLICT (device_id, period_start_ms, period_end_ms) DO UPDATE SET"
        "   session_count = excluded.session_count,"
        "   facts = excluded.facts,"
        "   summary = excluded.summary,"
        "   embedding = excluded.embedding,"
        "   embedding_model = excluded.embedding_model,"
        "   chat_model = excluded.chat_model,"
        "   generated_at = now()",
        device_id,
        period_start_ms,
        period_end_ms,
        len(sessions),
        json.dumps(facts),
        summary,
        to_pgvector(embedding),
        embedder.name,
        chat.name,
    )
    return f"written ({generate_seconds:.1f}s, {len(summary)} chars)"


async def run(
    *, device_id: str | None, window_days: int, force: bool, dry_run: bool
) -> int:
    settings = get_settings()
    configure_logging(settings.log_level)

    now_ms = int(time.time() * 1000)
    connection = await asyncpg.connect(settings.asyncpg_dsn)
    client = build_client(settings)

    try:
        chat = build_batch_chat_provider(client, settings)
        embedder = build_embedding_provider(client, settings)

        if device_id:
            targets = [device_id]
        else:
            targets = await devices_with_sessions(
                connection, since_ms=max(0, now_ms - window_days * DAY_MS)
            )

        logger.info(
            "summarize starting",
            extra={
                "devices": len(targets),
                "window_days": window_days,
                "chat_model": chat.name,
                "embedding_model": embedder.name,
            },
        )

        failures = 0
        for target in targets:
            try:
                outcome = await summarize_device(
                    connection,
                    target,
                    chat,
                    embedder,
                    now_ms=now_ms,
                    window_days=window_days,
                    force=force,
                    dry_run=dry_run,
                )
                logger.info(
                    "summarize device", extra={"device_id": target, "outcome": outcome}
                )
            except ProviderError as exc:
                # Per device, so one timeout does not cost every other device its
                # summary. The loop keeps going and the exit code remembers.
                failures += 1
                logger.error(
                    "summarize failed",
                    extra={"device_id": target, "error": str(exc)},
                )

        logger.info(
            "summarize finished",
            extra={"devices": len(targets), "failures": failures},
        )
        return 1 if failures else 0
    finally:
        await client.aclose()
        await connection.close()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "--device-id", help="Summarize one device. Default is every device with sessions."
    )
    parser.add_argument("--window-days", type=int, default=30)
    parser.add_argument(
        "--force",
        action="store_true",
        help="Regenerate even if the window is unchanged. What a prompt change needs.",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Generate and print, write nothing. Reads the database, calls the model.",
    )
    args = parser.parse_args()
    return asyncio.run(
        run(
            device_id=args.device_id,
            window_days=args.window_days,
            force=args.force,
            dry_run=args.dry_run,
        )
    )


if __name__ == "__main__":
    sys.exit(main())
