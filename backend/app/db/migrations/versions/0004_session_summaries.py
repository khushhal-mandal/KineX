"""session_summaries — the narrative, its inputs, and its embedding

The first table in this schema that stores a vector. Migration 0001 created the `vector`
extension and left it unused precisely so that this revision would be about the column
rather than about whether the image was right.

One row is one batch run over one device's sessions: the facts that were read, the
narrative the local model wrote from them, and the embedding of that narrative. All three
together, because a summary you cannot audit against its inputs is a summary you cannot
tell from a hallucination.

Revision ID: 0004
Revises: 0003
Create Date: 2026-08-22
"""

from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

revision: str = "0004"
down_revision: Union[str, None] = "0003"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


# nomic-embed-text's output width. A four-line type rather than the `pgvector` package,
# which would be a runtime dependency added for one DDL statement — nothing else in this
# project needs Python to understand a vector, because the distance is computed in
# Postgres and the column is never selected back. See app/coach/retrieval.py.
EMBEDDING_DIMENSIONS = 768


class Vector(sa.types.UserDefinedType):
    cache_ok = True

    def __init__(self, dimensions: int) -> None:
        self.dimensions = dimensions

    def get_col_spec(self, **kw: object) -> str:
        return f"vector({self.dimensions})"


def upgrade() -> None:
    op.create_table(
        "session_summaries",
        sa.Column("id", sa.BigInteger(), sa.Identity(always=True), primary_key=True),
        sa.Column(
            "device_id",
            sa.Text(),
            nullable=False,
            comment=(
                "Whose training this describes. Every read of this table filters on it, "
                "including the vector search — an embedding is not an access control "
                "boundary and nearest-neighbour over all devices would return other "
                "people's workouts, ranked by how well they match the question."
            ),
        ),
        sa.Column(
            "period_start_ms",
            sa.BigInteger(),
            nullable=False,
            comment=(
                "started_at_ms of the earliest session covered. Derived from the sessions "
                "actually read, not from the clock when the job ran, which is what makes "
                "a re-run over the same sessions collide instead of writing a second row."
            ),
        ),
        sa.Column(
            "period_end_ms",
            sa.BigInteger(),
            nullable=False,
            comment="started_at_ms of the latest session covered. Inclusive.",
        ),
        sa.Column(
            "session_count",
            sa.Integer(),
            nullable=False,
            comment="How many sessions went into this summary.",
        ),
        sa.Column(
            "facts",
            postgresql.JSONB(),
            nullable=False,
            comment=(
                "The aggregates the narrative was generated from, stored verbatim. This "
                "is the audit trail: a summary claiming a squat PR can be checked against "
                "the numbers the model was actually handed. Also what makes a bad summary "
                "diagnosable as a prompt problem or a retrieval problem."
            ),
        ),
        sa.Column(
            "summary",
            sa.Text(),
            nullable=False,
            comment="The narrative itself, written by the local model. What gets embedded.",
        ),
        sa.Column(
            "embedding",
            Vector(EMBEDDING_DIMENSIONS),
            nullable=False,
            comment=(
                "The summary text, embedded. 768 dimensions because that is "
                "nomic-embed-text's width; the model normalises its output to unit "
                "length, so cosine distance (<=>) and inner product agree here."
            ),
        ),
        sa.Column(
            "embedding_model",
            sa.Text(),
            nullable=False,
            comment=(
                "Which model produced `embedding`. Stored per row because vectors from "
                "two different models are not comparable — the distance between them is "
                "noise, and it is noise that looks exactly like a number. Changing the "
                "embedding model means re-embedding every row, and this column is how a "
                "later migration finds the rows that still need it."
            ),
        ),
        sa.Column(
            "chat_model",
            sa.Text(),
            nullable=False,
            comment="Which model wrote `summary`. Not the same model that embedded it.",
        ),
        sa.Column(
            "generated_at",
            sa.TIMESTAMP(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.ForeignKeyConstraint(
            ["device_id"],
            ["devices.device_id"],
            name="fk_session_summaries_device",
            ondelete="CASCADE",
        ),
        # Re-running the job over an unchanged set of sessions must not accumulate rows.
        # Unlike sessions, which collide into DO NOTHING, this one is upserted: a session
        # is an immutable record of something that happened, whereas a summary is a
        # derived artifact that a better model or a better prompt should be able to
        # replace in place. See app/jobs/summarize.py.
        sa.UniqueConstraint(
            "device_id",
            "period_start_ms",
            "period_end_ms",
            name="uq_session_summaries_device_period",
        ),
        comment=(
            "One batch-generated training narrative with the facts behind it and its "
            "embedding. Written by app/jobs/summarize.py; read by POST /coach/chat."
        ),
    )

    # The batch job's own lookup: this device's most recent summary, to decide whether
    # anything has changed since it last ran.
    op.create_index(
        "ix_session_summaries_device_generated",
        "session_summaries",
        ["device_id", sa.text("generated_at DESC")],
    )

    # There is deliberately NO vector index, and adding one now would make retrieval
    # worse rather than better. Every search filters to one device first, which is a
    # handful of rows; an exact scan over those is faster than an approximate index, and
    # an ivfflat index built on a nearly-empty table produces bad recall permanently
    # because its centroids are fitted to whatever was there at build time. HNSW becomes
    # worth it when a single device has thousands of summaries — one a night, so years.


def downgrade() -> None:
    op.drop_index("ix_session_summaries_device_generated", table_name="session_summaries")
    op.drop_table("session_summaries")
    # The `vector` extension is left alone, as in 0001: it is a property of the database
    # rather than of this revision.
