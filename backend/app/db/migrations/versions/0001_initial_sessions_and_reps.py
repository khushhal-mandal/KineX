"""initial sessions and reps

Mirrors the device's Room schema, ``app/schemas/com.kinex.data.KineXDatabase/1.json``
(identity hash 2b5ea8409e30321fd240c70a2688c263) — same fields, same meanings.

Room column          -> Postgres column
  sessions.id           (not mirrored; becomes sessions.client_session_id)
  sessions.exerciseId   sessions.exercise_id
  sessions.startedAtMs  sessions.started_at_ms
  sessions.durationMs   sessions.duration_ms
  sessions.repCount     sessions.rep_count
  reps.id               (not mirrored; the device-local rep id is not carried)
  reps.sessionId        reps.session_id      (re-pointed at the server's sessions.id)
  reps.repIndex         reps.rep_index
  reps.peakProgress     reps.peak_progress
  reps.violationMask    reps.violation_mask

Identifiers are snake_case because unquoted camelCase folds to lowercase in Postgres,
so mirroring Room's spelling would mean double-quoting every identifier forever. The
fields and their meanings are what is mirrored, not the spelling.

Two columns exist that Room has no counterpart for — device_id and client_session_id —
and together they are the idempotency key. See the comment on the unique constraint.

Revision ID: 0001
Revises:
Create Date: 2026-08-21
"""

from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa

revision: str = "0001"
down_revision: Union[str, None] = None
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    # The reason this database is Postgres rather than anything else: relational data
    # and embeddings in one box. Nothing stores a vector yet; the extension is created
    # here so that "is this the right image" is answered by the migration rather than
    # by whoever first writes an embedding.
    op.execute("CREATE EXTENSION IF NOT EXISTS vector")

    op.create_table(
        "sessions",
        sa.Column(
            "id",
            sa.BigInteger(),
            sa.Identity(always=True),
            primary_key=True,
            comment="Server-side primary key. Never sent by a device and never equal to the device's own row id.",
        ),
        sa.Column(
            "device_id",
            sa.Text(),
            nullable=False,
            comment=(
                "Opaque device identity supplied by the client. Phase 8 makes this a "
                "foreign key to a devices table keyed by the anonymous keypair; there is "
                "no auth yet, so it is a bare column."
            ),
        ),
        sa.Column(
            "client_session_id",
            sa.BigInteger(),
            nullable=False,
            comment=(
                "The device's own sessions.id, from Room's INTEGER PRIMARY KEY "
                "AUTOINCREMENT. AUTOINCREMENT rather than a bare rowid matters here: it "
                "never reuses the id of a deleted row, so the value is stable for the "
                "life of that database file."
            ),
        ),
        sa.Column(
            "exercise_id",
            sa.Integer(),
            nullable=False,
            comment=(
                "Room sessions.exerciseId. Stored raw, as the app stores it: a row "
                "written under an id this build does not know is a record of something "
                "that happened, not an error."
            ),
        ),
        sa.Column(
            "started_at_ms",
            sa.BigInteger(),
            nullable=False,
            comment=(
                "Room sessions.startedAtMs. Wall-clock epoch milliseconds, and the only "
                "wall-clock reading in the record — it exists to put a date on a row."
            ),
        ),
        sa.Column(
            "duration_ms",
            sa.BigInteger(),
            nullable=False,
            comment=(
                "Room sessions.durationMs. First counted rep to last, on MediaPipe's "
                "monotonic frame clock — not screen-open to timeout. A one-rep set is 0, "
                "which is true rather than missing."
            ),
        ),
        sa.Column(
            "rep_count",
            sa.Integer(),
            nullable=False,
            comment="Room sessions.repCount. A set with no reps is never written, on the device or here.",
        ),
        sa.Column(
            "received_at",
            sa.TIMESTAMP(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
            comment=(
                "Server-side ingest time. Not mirrored from Room; it is the one thing the "
                "mirrored fields cannot answer — when the workout synced, as opposed to "
                "when it happened."
            ),
        ),
        # Idempotency. A retried sync carries the same (device_id, client_session_id) and
        # collides here, so the retry becomes a no-op rather than a second workout.
        sa.UniqueConstraint(
            "device_id", "client_session_id", name="uq_sessions_device_client_session"
        ),
        comment="One completed set, as recorded on a device and synced here.",
    )

    op.create_table(
        "reps",
        sa.Column("id", sa.BigInteger(), sa.Identity(always=True), primary_key=True),
        sa.Column(
            "session_id",
            sa.BigInteger(),
            nullable=False,
            comment=(
                "Room reps.sessionId, re-pointed at the server's sessions.id. The "
                "device-local session id is not carried on this row; it is on the parent."
            ),
        ),
        sa.Column(
            "rep_index",
            sa.Integer(),
            nullable=False,
            comment="Room reps.repIndex. Position of the rep within its set.",
        ),
        sa.Column(
            "peak_progress",
            sa.REAL(),
            nullable=False,
            comment=(
                "Room reps.peakProgress — engine slot [6], the rep's high-water progress, "
                "UNCLAMPED. REAL because the device value is a 32-bit Kotlin Float. There "
                "is deliberately no CHECK bounding this at 1.0: a stored 1.00 that was "
                "really 1.02 cannot be un-flattened by any later migration, and the 20 Aug "
                "2026 device sweep recorded a genuine 38.75 from a collapsed calibration "
                "span. Slot [4] is the clamped one and is not stored anywhere."
            ),
        ),
        sa.Column(
            "violation_mask",
            sa.Integer(),
            nullable=False,
            comment=(
                "Room reps.violationMask. Bitfield, decoded client-side — no violation "
                "strings cross this boundary any more than they cross JNI. 0 means no bit "
                "was set, which is not the same as the rep having no faults: nine of the "
                "ten exercises ship with violation_rules = 0."
            ),
        ),
        sa.ForeignKeyConstraint(
            ["session_id"],
            ["sessions.id"],
            name="fk_reps_session",
            ondelete="CASCADE",
        ),
        # Mirrors the device's own rep identity within a set, and makes the reps half of
        # a retried sync idempotent too: re-sending a session's reps collides here.
        sa.UniqueConstraint("session_id", "rep_index", name="uq_reps_session_rep_index"),
        comment="One counted rep. Mirrors Room's reps table, including its CASCADE.",
    )

    # Room carries index_reps_sessionId; the same lookup is the same shape here.
    op.create_index("ix_reps_session_id", "reps", ["session_id"])


def downgrade() -> None:
    op.drop_index("ix_reps_session_id", table_name="reps")
    op.drop_table("reps")
    op.drop_table("sessions")
    # The vector extension is deliberately not dropped. It is a property of the database
    # rather than of this revision, and dropping it would take any vector column with it.
