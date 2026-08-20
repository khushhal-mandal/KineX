"""client_session_id becomes a device-generated uuid

Closes the collision described in 0001: a device whose local database is wiped while its
identity survives restarts Room's AUTOINCREMENT at 1, and those ids collide with sessions
already synced under the same device_id. The collision resolves the wrong way — a *new*
workout matches an existing key and is silently dropped as a duplicate retry. `pm clear`
destroyed that database once already, on 19 Aug 2026, and Phase 9's recovery-phrase
restore makes it likely rather than possible.

No server-side change could fix it: `1` is `1`. The identifier has to be one the device
cannot accidentally re-mint, so it becomes a UUID the device generates per session.

The unique constraint keeps its shape — same name, same two columns, same meaning. It is
dropped and recreated only because a column's type cannot be altered out from under an
index that depends on it.

**Postgres `uuid` rather than `text` is load-bearing, not tidiness.** The `uuid` type
normalizes case and hyphenation on the way in, so a device that sends `A1B2-...` and one
that sends `a1b2-...` for the same session collide on `ON CONFLICT` as they should. Stored
as `text` those are two distinct values and the retry this whole scheme exists to stop
would write a second workout. It also rejects a malformed identifier at the boundary
instead of storing it.

Revision ID: 0002
Revises: 0001
Create Date: 2026-08-21
"""

from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa

revision: str = "0002"
down_revision: Union[str, None] = "0001"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None

_CONSTRAINT = "uq_sessions_device_client_session"

_COMMENT = (
    "A UUID the device generates when it inserts the session into its own Room database, "
    "and never regenerates. Idempotency key with device_id. It replaced Room's "
    "AUTOINCREMENT rowid in migration 0002: a rowid is only unique within one database "
    "file, so a wiped-and-rebuilt device restarts at 1 and its new sessions collide with "
    "its old ones."
)


def _refuse_if_populated(direction: str) -> None:
    """There is no conversion between a rowid and a UUID, so this migration discards
    whatever is in the column. That is free today — nothing has ever synced — and it is
    data loss the moment it is not. Postgres would refuse anyway when NOT NULL met the
    existing rows, but it would say "column contains null values", which is not a
    sentence that tells you what you just lost."""
    rows = op.get_bind().execute(sa.text("SELECT count(*) FROM sessions")).scalar_one()
    if rows:
        raise RuntimeError(
            f"sessions holds {rows} row(s); {direction} discards client_session_id and "
            "cannot reconstruct it. Nothing had synced when this migration was written. "
            "Export the table before forcing it."
        )


def upgrade() -> None:
    _refuse_if_populated("upgrade 0002")

    op.drop_constraint(_CONSTRAINT, "sessions", type_="unique")
    op.drop_column("sessions", "client_session_id")
    op.add_column(
        "sessions",
        sa.Column("client_session_id", sa.UUID(), nullable=False, comment=_COMMENT),
    )
    op.create_unique_constraint(
        _CONSTRAINT, "sessions", ["device_id", "client_session_id"]
    )


def downgrade() -> None:
    _refuse_if_populated("downgrade to 0001")

    op.drop_constraint(_CONSTRAINT, "sessions", type_="unique")
    op.drop_column("sessions", "client_session_id")
    op.add_column(
        "sessions",
        sa.Column(
            "client_session_id",
            sa.BigInteger(),
            nullable=False,
            comment=(
                "The device's own sessions.id, from Room's INTEGER PRIMARY KEY "
                "AUTOINCREMENT. See migration 0002 for why this is not enough."
            ),
        ),
    )
    op.create_unique_constraint(
        _CONSTRAINT, "sessions", ["device_id", "client_session_id"]
    )
