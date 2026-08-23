"""Hybrid retrieval: structured facts from SQL, narratives from pgvector.

Both halves exist because they answer different questions and neither can answer the
other's. "How many squats did I do in July" is a `sum()` — an embedding of a narrative
would produce a plausible number rather than the number. "Am I getting discouraged on
leg day" is nowhere in the schema and lives only in prose. Retrieving one way and
answering both kinds of question is how a coaching bot ends up inventing statistics.

**Every query in this module filters on device_id, and there is no code path that does
not.** For the SQL half that is obvious. For the vector half it is the thing most easily
left out, because nearest-neighbour search returns results either way: without the
filter, a question about squats returns whichever *other person's* summary is most
similar to it, ranked and formatted exactly like a correct answer. An embedding is a
similarity function, not an access control boundary.

Vectors are written as a text literal and cast with `::vector`, and the `embedding`
column is never selected back into Python. That is what lets this project store
embeddings without the `pgvector` package: the distance is computed in Postgres, which is
the entire reason the vectors are in Postgres.
"""

import asyncpg

# A display mirror of the exercise config table in the app design doc. Deliberately a
# copy: the backend stores `exercise_id` raw, and an id this table does not know is still
# a record of something that happened. Drift costs a label in prose and nothing else,
# which is why a shared source of truth is not worth building for it.
EXERCISE_NAMES: dict[int, str] = {
    0: "Squat",
    1: "Push-up",
    2: "Bicep curl",
    3: "Shoulder press",
    4: "Lateral raise",
    5: "Sit-up",
    6: "Lunge",
    7: "Glute bridge",
    8: "Jumping jack",
    9: "Leg raise",
}

# The exercises that actually have form rules. Nine of the ten ship with
# `violation_rules = 0`, so a violation_mask of 0 on a push-up does not mean the form was
# good — it means nothing was ever checked. Without this distinction the model is handed
# "0 violations" and congratulates someone on flawless push-ups it never looked at, which
# is a fabrication that reads exactly like a fact. Kept in sync with the same table.
FORM_CHECKED_EXERCISE_IDS: frozenset[int] = frozenset({0})

DAY_MS = 86_400_000


def exercise_name(exercise_id: int) -> str:
    return EXERCISE_NAMES.get(exercise_id, f"exercise {exercise_id}")


def to_pgvector(values: list[float]) -> str:
    """A vector in pgvector's text input form, for `$n::vector`.

    Passed as text rather than through a type codec because this is the only direction
    that is ever needed — vectors go in, distances come out. The column is `vector`,
    whose storage is float4, so the extra digits `repr` emits are discarded by Postgres
    rather than stored.
    """
    return "[" + ",".join(repr(float(value)) for value in values) + "]"


async def training_facts(
    connection: asyncpg.Connection,
    device_id: str,
    *,
    now_ms: int,
    window_days: int = 90,
    trend_days: int = 14,
    recent_sessions: int = 10,
) -> dict:
    """Everything the structured half knows about one device, as a JSON-able dict.

    This is both the batch job's input and the chat prompt's grounding, which is
    deliberate: a summary and a live answer that disagree about how many squats happened
    would be worse than either alone.
    """
    window_start_ms = max(0, now_ms - window_days * DAY_MS)
    recent_start_ms = max(0, now_ms - trend_days * DAY_MS)
    prior_start_ms = max(0, now_ms - 2 * trend_days * DAY_MS)

    per_exercise = await connection.fetch(
        "WITH scoped AS ("
        "    SELECT id, exercise_id, started_at_ms, duration_ms, rep_count"
        "    FROM sessions WHERE device_id = $1 AND started_at_ms >= $2"
        "),"
        " per_session AS ("
        "    SELECT exercise_id, count(*) AS sessions, sum(rep_count) AS reps,"
        "           min(started_at_ms) AS first_ms, max(started_at_ms) AS last_ms,"
        # ::bigint is not decoration. sum() over a bigint column returns `numeric` in
        # Postgres, which asyncpg hands back as a Decimal, which json.dumps refuses —
        # and the only place that shows up is the batch job serialising `facts`, i.e.
        # in production rather than in any query anyone runs by hand. sum() over the
        # `integer` columns returns bigint and is already an int.
        "           sum(duration_ms)::bigint AS total_duration_ms"
        "    FROM scoped GROUP BY exercise_id"
        "),"
        " per_rep AS ("
        "    SELECT s.exercise_id, count(*) AS reps_recorded,"
        # Median, not mean. peak_progress is stored unclamped on purpose and a collapsed
        # calibration span has produced a genuine 38.75 — one of those in a mean makes
        # "your typical depth" a number from another planet. A median absorbs it without
        # needing a threshold to decide what counts as an outlier.
        "           percentile_cont(0.5) WITHIN GROUP (ORDER BY r.peak_progress)"
        "               AS median_peak_progress,"
        "           count(*) FILTER (WHERE r.violation_mask <> 0) AS reps_with_violations,"
        # Reported, never filtered — it annotates the data rather than editing it, so a
        # calibration bug stays visible instead of being quietly averaged away.
        "           count(*) FILTER (WHERE r.peak_progress > 1.5) AS implausible_reps"
        "    FROM scoped s JOIN reps r ON r.session_id = s.id"
        "    GROUP BY s.exercise_id"
        ")"
        " SELECT p.exercise_id, p.sessions, p.reps, p.first_ms, p.last_ms,"
        "        p.total_duration_ms, r.reps_recorded, r.median_peak_progress,"
        "        r.reps_with_violations, r.implausible_reps"
        " FROM per_session p LEFT JOIN per_rep r ON r.exercise_id = p.exercise_id"
        " ORDER BY p.sessions DESC, p.exercise_id",
        device_id,
        window_start_ms,
    )

    trend = await connection.fetch(
        "WITH scoped AS ("
        "    SELECT id, exercise_id,"
        "           CASE WHEN started_at_ms >= $2 THEN 'recent' ELSE 'prior' END AS bucket"
        "    FROM sessions"
        "    WHERE device_id = $1 AND started_at_ms >= $3"
        ")"
        " SELECT s.exercise_id, s.bucket, count(*) AS reps,"
        "        percentile_cont(0.5) WITHIN GROUP (ORDER BY r.peak_progress)"
        "            AS median_peak_progress"
        " FROM scoped s JOIN reps r ON r.session_id = s.id"
        " GROUP BY s.exercise_id, s.bucket",
        device_id,
        recent_start_ms,
        prior_start_ms,
    )

    weekly = await connection.fetch(
        # ISO week, so a week is Monday-to-Sunday and the label sorts lexically. UTC
        # rather than a device timezone, which is not a thing this schema records.
        "SELECT to_char(to_timestamp(started_at_ms / 1000.0) AT TIME ZONE 'UTC',"
        "               'IYYY-\"W\"IW') AS week,"
        "       count(*) AS sessions, sum(rep_count) AS reps"
        " FROM sessions WHERE device_id = $1 AND started_at_ms >= $2"
        " GROUP BY week ORDER BY week",
        device_id,
        window_start_ms,
    )

    latest = await connection.fetch(
        "SELECT started_at_ms, exercise_id, rep_count, duration_ms"
        " FROM sessions WHERE device_id = $1"
        " ORDER BY started_at_ms DESC, id DESC LIMIT $2",
        device_id,
        recent_sessions,
    )

    totals = await connection.fetchrow(
        "SELECT count(*) AS sessions, coalesce(sum(rep_count), 0) AS reps,"
        "       min(started_at_ms) AS first_ms, max(started_at_ms) AS last_ms"
        " FROM sessions WHERE device_id = $1",
        device_id,
    )

    buckets: dict[int, dict[str, dict]] = {}
    for row in trend:
        buckets.setdefault(row["exercise_id"], {})[row["bucket"]] = {
            "reps": row["reps"],
            "median_peak_progress": _round(row["median_peak_progress"]),
        }

    return {
        "window_days": window_days,
        "trend_days": trend_days,
        "totals": {
            "sessions": totals["sessions"],
            "reps": totals["reps"],
            "first_session_ms": totals["first_ms"],
            "last_session_ms": totals["last_ms"],
        },
        "by_exercise": [
            {
                "exercise_id": row["exercise_id"],
                "exercise": exercise_name(row["exercise_id"]),
                "sessions": row["sessions"],
                "reps": row["reps"],
                "reps_recorded": row["reps_recorded"] or 0,
                "median_peak_progress": _round(row["median_peak_progress"]),
                "form_checked": row["exercise_id"] in FORM_CHECKED_EXERCISE_IDS,
                "reps_with_violations": (
                    row["reps_with_violations"]
                    if row["exercise_id"] in FORM_CHECKED_EXERCISE_IDS
                    else None
                ),
                "implausible_reps": row["implausible_reps"] or 0,
                "first_session_ms": row["first_ms"],
                "last_session_ms": row["last_ms"],
                "total_duration_ms": row["total_duration_ms"],
            }
            for row in per_exercise
        ],
        "depth_trend": [
            {
                "exercise_id": exercise_id,
                "exercise": exercise_name(exercise_id),
                "recent": sides.get("recent"),
                "prior": sides.get("prior"),
            }
            for exercise_id, sides in sorted(buckets.items())
        ],
        "weekly": [
            {"week": row["week"], "sessions": row["sessions"], "reps": row["reps"]}
            for row in weekly
        ],
        "recent_sessions": [
            {
                "started_at_ms": row["started_at_ms"],
                "exercise_id": row["exercise_id"],
                "exercise": exercise_name(row["exercise_id"]),
                "rep_count": row["rep_count"],
                "duration_ms": row["duration_ms"],
            }
            for row in latest
        ],
    }


async def search_summaries(
    connection: asyncpg.Connection,
    device_id: str,
    query_vector: list[float],
    *,
    limit: int = 4,
) -> list[dict]:
    """The nearest stored narratives to a question, for this device only.

    `WHERE device_id = $1` is the single most important line in this module. Removing it
    does not break the query, does not raise, and does not look wrong in a code review —
    it just starts answering one person's questions with another person's training. There
    is a test that removes it and expects failure.

    `<=>` is cosine distance. nomic-embed-text returns unit-length vectors, so this and
    inner product rank identically; cosine is used because it stays correct if a future
    model does not normalise.
    """
    rows = await connection.fetch(
        "SELECT id, period_start_ms, period_end_ms, session_count, summary,"
        "       generated_at, embedding <=> $2::vector AS distance"
        " FROM session_summaries"
        " WHERE device_id = $1"
        " ORDER BY embedding <=> $2::vector"
        " LIMIT $3",
        device_id,
        to_pgvector(query_vector),
        limit,
    )
    return [
        {
            "id": row["id"],
            "period_start_ms": row["period_start_ms"],
            "period_end_ms": row["period_end_ms"],
            "session_count": row["session_count"],
            "summary": row["summary"],
            "generated_at": row["generated_at"],
            "distance": row["distance"],
        }
        for row in rows
    ]


async def sessions_in_window(
    connection: asyncpg.Connection, device_id: str, *, since_ms: int
) -> list[asyncpg.Record]:
    """The sessions a batch summary will cover. Ordered oldest first, as a story is."""
    return await connection.fetch(
        "SELECT id, exercise_id, started_at_ms, duration_ms, rep_count"
        " FROM sessions WHERE device_id = $1 AND started_at_ms >= $2"
        " ORDER BY started_at_ms, id",
        device_id,
        since_ms,
    )


async def devices_with_sessions(
    connection: asyncpg.Connection, *, since_ms: int
) -> list[str]:
    """Every device that trained in the window. The batch job's work list.

    The one query in this module without a device_id filter, because producing that list
    is what it is for. It returns ids and nothing else — no training data crosses a
    device boundary here.
    """
    rows = await connection.fetch(
        "SELECT DISTINCT device_id FROM sessions WHERE started_at_ms >= $1"
        " ORDER BY device_id",
        since_ms,
    )
    return [row["device_id"] for row in rows]


# Every figure in a fact row that describes how *well* a rep was performed, as opposed to
# how many there were. `form_checked` is in the list because its absence is what tells the
# renderer to print no form clause at all — a row that still carried `form_checked: false`
# would render as "form not checked", which is the exact string a 3B model paraphrased
# into "with no form issues noted".
QUALITY_FIELDS = (
    "median_peak_progress",
    "reps_with_violations",
    "implausible_reps",
    "form_checked",
)


def drop_unassessed_quality_metrics(facts: dict) -> dict:
    """Strip every quality figure from exercises the app never assesses. Volume stays.

    **This is the structural half of a fix that a prompt instruction could not make
    stick.** `SUMMARY_SYSTEM` used to carry a rule saying not to comment on the form of
    unchecked exercises, and the data still carried a "form not checked" label for the
    model to misread. On 22 Aug 2026 `qwen2.5:3b` read that label and wrote *"The Push-ups
    have remained at a consistent depth of 1.00, with no form issues noted"* — a technique
    claim about an exercise no camera ever assessed, sitting in the same paragraph as real
    numbers and indistinguishable from them.

    A model cannot fabricate from a field it never received. So depth, the violation
    count, the implausible-rep flag and the label itself are removed here, before the
    prompt is built and before `facts` is stored — which keeps the stored audit trail
    equal to what the model was actually handed rather than to what was queried.

    What survives is volume: sessions, reps, dates. Those are counted for every exercise
    and are true regardless of whether technique was assessed, so a nightly log still
    reports that someone trained push-ups three times, just not how well.

    Applied on the batch path only. The chat path keeps the label deliberately, because
    "your push-ups haven't been checked for form" is a useful and correct answer to
    "how's my push-up form" — and the hosted model gives it reliably, where the local 3B
    one does not.
    """
    assessed = {
        row["exercise_id"] for row in facts["by_exercise"] if row.get("form_checked")
    }
    trimmed = dict(facts)
    trimmed["by_exercise"] = [
        row
        if row["exercise_id"] in assessed
        else {key: value for key, value in row.items() if key not in QUALITY_FIELDS}
        for row in facts["by_exercise"]
    ]
    # The depth trend is nothing but a quality figure, so an unassessed exercise leaves it
    # entirely rather than appearing with the numbers removed.
    trimmed["depth_trend"] = [
        row for row in facts["depth_trend"] if row["exercise_id"] in assessed
    ]
    return trimmed


def _round(value: float | None) -> float | None:
    """Two decimals. The model is being told about progress ratios, not asked to do
    arithmetic on them, and `0.9333333333333333` in a prompt is tokens spent on noise."""
    return None if value is None else round(float(value), 2)
