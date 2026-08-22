"""devices, auth challenges, crashes, and the sessions FK Phase 8 owed

Anonymous keypair auth, per the root design doc: no email, no password, no PII. A device
is its Ed25519 public key and nothing else. There is no user table because there are no
users — there are only keys.

Everything base64url here is **unpadded**, matching app/auth/keys.py and the contract in
the root design doc.

Revision ID: 0003
Revises: 0002
Create Date: 2026-08-22
"""

from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

revision: str = "0003"
down_revision: Union[str, None] = "0002"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.create_table(
        "devices",
        sa.Column(
            "device_id",
            sa.Text(),
            primary_key=True,
            comment=(
                "base64url(sha256(raw public key)), unpadded — 43 chars. Derived rather "
                "than random so that re-registering the same key is the same device, "
                "which is what makes a recovery-phrase restore land on the existing "
                "account instead of silently creating a second one."
            ),
        ),
        sa.Column(
            "public_key",
            sa.Text(),
            nullable=False,
            unique=True,
            comment=(
                "The device's Ed25519 public key: 32 raw bytes, base64url, unpadded. The "
                "entire account. There is no email, password or other identifier, here "
                "or anywhere else in this schema."
            ),
        ),
        sa.Column(
            "created_at",
            sa.TIMESTAMP(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column(
            "last_seen_at",
            sa.TIMESTAMP(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
            comment="Updated when a token is issued. The only activity signal stored.",
        ),
        comment="An anonymous account, which is to say a public key.",
    )

    op.create_table(
        "auth_challenges",
        sa.Column(
            "nonce",
            sa.Text(),
            primary_key=True,
            comment=(
                "32 CSPRNG bytes, base64url, unpadded. Never a timestamp and never a "
                "counter — a guessable challenge is not a challenge."
            ),
        ),
        sa.Column(
            "public_key",
            sa.Text(),
            nullable=False,
            comment=(
                "The key this challenge was issued to. Checked on redemption, so a nonce "
                "handed to one device cannot be spent by another."
            ),
        ),
        sa.Column(
            "expires_at",
            sa.TIMESTAMP(timezone=True),
            nullable=False,
            comment="Sixty seconds out. Redemption deletes the row, so this only bounds how long an unspent challenge lingers.",
        ),
        comment=(
            "Single-use challenges. A row's lifetime is one redemption or one minute, "
            "whichever comes first; expired rows are swept on each issue."
        ),
    )
    # Supports the sweep, which deletes by expiry on every challenge issued.
    op.create_index("ix_auth_challenges_expires_at", "auth_challenges", ["expires_at"])

    op.create_table(
        "crashes",
        sa.Column("id", sa.BigInteger(), sa.Identity(always=True), primary_key=True),
        sa.Column(
            "device_id",
            sa.Text(),
            nullable=True,
            comment=(
                "NULL when the report arrived without a usable token, which is allowed "
                "and expected — see app/api/crashes.py. Never read from the request "
                "body; it comes from the JWT or it is NULL."
            ),
        ),
        sa.Column(
            "received_at",
            sa.TIMESTAMP(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column(
            "payload",
            postgresql.JSONB(),
            nullable=False,
            comment=(
                "ACRA's report, stored whole and unparsed. Deliberately not split into "
                "columns: ACRA's shape changes between versions and a parser that got it "
                "wrong would drop reports. A body that is not JSON at all is wrapped as "
                "{\"_raw\": \"...\"} rather than rejected."
            ),
        ),
        sa.ForeignKeyConstraint(
            ["device_id"],
            ["devices.device_id"],
            name="fk_crashes_device",
            ondelete="SET NULL",
        ),
        comment="ACRA crash reports. The one endpoint that accepts an unauthenticated write.",
    )
    op.create_index(
        "ix_crashes_received_at", "crashes", [sa.text("received_at DESC")]
    )

    # The foreign key 0001 said Phase 8 would add. sessions.device_id stops being a bare
    # text column that anything could claim and starts meaning "a key that authenticated".
    op.create_foreign_key(
        "fk_sessions_device",
        "sessions",
        "devices",
        ["device_id"],
        ["device_id"],
        ondelete="CASCADE",
    )
    # Every authenticated read is "this device's sessions, newest first". Without this
    # the FK's own lookups and GET /sessions both fall back to a sequential scan.
    op.create_index(
        "ix_sessions_device_started",
        "sessions",
        ["device_id", sa.text("started_at_ms DESC"), sa.text("id DESC")],
    )


def downgrade() -> None:
    op.drop_index("ix_sessions_device_started", table_name="sessions")
    op.drop_constraint("fk_sessions_device", "sessions", type_="foreignkey")
    op.drop_index("ix_crashes_received_at", table_name="crashes")
    op.drop_table("crashes")
    op.drop_index("ix_auth_challenges_expires_at", table_name="auth_challenges")
    op.drop_table("auth_challenges")
    op.drop_table("devices")
