"""Prompt assembly for both paths.

The facts are rendered as short lines rather than as JSON. A 3B model spends a
meaningful fraction of its attention on punctuation it does not need, and epoch
milliseconds are worse than useless in a prompt — `1755820800000` is not a date to a
language model, it is five tokens of noise. Everything a model sees here is already in
the units a person would use.

Both system prompts say the same thing in different words, and it is the only thing that
really matters: **answer from the supplied block or say you do not know.** A coaching
assistant that invents a squat count is worse than one that declines, because the
invented number is indistinguishable from the real ones sitting next to it.
"""

from datetime import datetime, timezone

CHAT_SYSTEM = """You are the KineX coach. You help one person understand their own \
strength-training history, recorded by an on-device pose tracker.

Rules, in order of importance:

1. Every number you state must come from the DATA block. Never estimate, extrapolate or \
round a figure that is not there. If the data does not answer the question, say so \
plainly and say what you would need.
2. Reps are counted by a camera. Where an exercise is marked "form not checked", the app \
counted the reps but never assessed technique — do not praise or criticise form for \
those exercises, and do not read a zero violation count as good form.
3. "Depth" is progress toward that exercise's target angle, where 1.00 is the full \
target. Slightly over 1.00 is normal. A value far above it is a calibration artifact, \
not a superhuman rep.
4. You are not a medical professional. No diagnoses, no rehab programmes, no advice \
about pain beyond suggesting they stop and see someone.
5. Two or three short paragraphs at most. Talk like a coach who has read the numbers, \
not like a report."""

SUMMARY_SYSTEM = """You are writing a short training log entry about one person's \
recent workouts, for that person to read later.

Write 120-200 words of plain prose. No headings, no bullet points, no markdown.

Cover, where the data supports it: what they trained and how often, whether volume or \
consistency changed, and any exercise that stands out for better or worse.

Constraints:
- Use only the figures in the DATA block. Invent nothing.
- Some exercises are listed with volume only. Describe how much and how often they were \
trained; you have no information about how well they were performed.
- Depth is a ratio of how far a rep travelled toward that exercise's target angle, where \
reaching the target exactly is a ratio of one. Slightly past the target is normal. A \
ratio several times the target is a calibration fault and should be described as a bad \
measurement, not an achievement.
- Do not address the reader as "you" more than a couple of times, and do not open with \
a greeting. This is a log entry, not a message."""


def _sessions(count: int) -> str:
    """`1 session` / `4 sessions`.

    Pluralised because these lines are read by a 3B model, and "1 sessions, 8 reps" is
    already odd enough to parse without also being ungrammatical.
    """
    return f"{count} session" if count == 1 else f"{count} sessions"


def _date(ms: int | None) -> str:
    if ms is None:
        return "unknown"
    return datetime.fromtimestamp(ms / 1000, tz=timezone.utc).strftime("%Y-%m-%d")


def render_facts(facts: dict) -> str:
    """The structured half, as lines a small model can actually use."""
    lines: list[str] = []

    totals = facts["totals"]
    if not totals["sessions"]:
        return "No sessions recorded."

    lines.append(
        f"All time: {_sessions(totals['sessions'])}, {totals['reps']} reps total, "
        f"from {_date(totals['first_session_ms'])} to {_date(totals['last_session_ms'])}."
    )

    if facts["by_exercise"]:
        lines.append("")
        lines.append(f"By exercise (last {facts['window_days']} days):")
        for row in facts["by_exercise"]:
            parts = [
                f"  {row['exercise']}: {_sessions(row['sessions'])},"
                f" {row['reps']} reps total",
                f"last on {_date(row['last_session_ms'])}",
            ]
            # Presence-driven, not value-driven, and that is the point: a fact row that
            # has been through `drop_unassessed_quality_metrics` has no depth key and no
            # `form_checked` key, so no clause about either is written and the model is
            # handed volume alone. Testing `row["form_checked"] is False` instead would
            # reintroduce "form not checked" — the string that got paraphrased into a
            # fabricated compliment. See that function for the full history.
            if row.get("median_peak_progress") is not None:
                parts.append(f"typical depth {row['median_peak_progress']:.2f}")
            if "form_checked" in row:
                if row["form_checked"]:
                    # Qualifier first. Written as "4 of 8 reps flagged for form", the
                    # model dropped the trailing clause and reported *"they usually
                    # completed four out of eight reps"* — a volume claim invented from
                    # a form statistic. A number whose meaning arrives after it is a
                    # number that can be read as a plain count.
                    parts.append(
                        f"form flagged on {row['reps_with_violations']}"
                        f" of {row['reps_recorded']} reps"
                    )
                else:
                    parts.append("form not checked")
            if row.get("implausible_reps"):
                parts.append(
                    f"{row['implausible_reps']} reps recorded above 1.5 depth"
                    " (likely bad calibration)"
                )
            lines.append(", ".join(parts))

    trend = [row for row in facts["depth_trend"] if row["recent"] and row["prior"]]
    if trend:
        lines.append("")
        lines.append(
            f"Depth, last {facts['trend_days']} days vs the {facts['trend_days']} before:"
        )
        for row in trend:
            recent = row["recent"]["median_peak_progress"]
            prior = row["prior"]["median_peak_progress"]
            if recent is None or prior is None:
                continue
            direction = "up" if recent > prior else "down" if recent < prior else "level"
            lines.append(
                f"  {row['exercise']}: {prior:.2f} -> {recent:.2f} ({direction}),"
                f" {row['prior']['reps']} then {row['recent']['reps']} reps"
            )

    if facts["weekly"]:
        lines.append("")
        lines.append("Sessions per week:")
        for row in facts["weekly"]:
            lines.append(
                f"  {row['week']}: {_sessions(row['sessions'])},"
                f" {row['reps']} reps total"
            )

    if facts["recent_sessions"]:
        lines.append("")
        lines.append("Most recent sessions, one line each:")
        for row in facts["recent_sessions"]:
            # "on <date>", because a bare leading date next to the ISO week labels above
            # was read as a week: the model reported *"the week of 2026-08-20"* for what
            # is a single session on that day.
            lines.append(
                f"  on {_date(row['started_at_ms'])}: {row['exercise']},"
                f" {row['rep_count']} reps"
            )

    return "\n".join(lines)


def summary_prompt(facts: dict) -> str:
    """The batch job's user turn.

    Expects facts that have already been through `drop_unassessed_quality_metrics` — the
    job applies it before both this call and the `facts` column, so the prompt and the
    stored audit trail carry the same figures.
    """
    return f"DATA\n----\n{render_facts(facts)}\n"


def chat_prompt(facts: dict, summaries: list[dict], question: str) -> str:
    """The interactive user turn: both retrieval halves, then the question.

    The question goes last. A small model handed a long context tends to answer whatever
    it read most recently, and what it should be answering is the question.

    The question is included verbatim and is not sanitised. It is worth being clear about
    why that is acceptable here rather than leaving it unsaid: retrieval has already been
    scoped to this device by SQL, so the worst a crafted question achieves is a strange
    answer about the asker's own workouts. Nothing the model emits is executed, stored,
    or shown to anyone else.
    """
    blocks = [f"DATA\n----\n{render_facts(facts)}"]

    if summaries:
        past = "\n\n".join(
            f"[{_date(row['period_start_ms'])} to {_date(row['period_end_ms'])},"
            f" {row['session_count']} sessions]\n{row['summary']}"
            for row in summaries
        )
        blocks.append(
            "PREVIOUS TRAINING NOTES\n"
            "-----------------------\n"
            "Written earlier from this person's own data. Background, not fact:"
            " prefer the DATA block above for any number.\n\n" + past
        )

    blocks.append(f"QUESTION\n--------\n{question}")
    return "\n\n".join(blocks)
