"""Anonymous keypair auth: two steps, no email, no password, no PII.

    POST /auth/challenge  { public_key }                     -> { nonce, expires_at }
    POST /auth/token      { public_key, nonce, signature }    -> { access_token, ... }

**Posting a public key is not authentication.** A public key is public — it crosses the
wire at registration and the server stores it — so issuing a token to whoever presents one
would hand any device's account to anyone who had seen its key. The signature over the
challenge is the entire security of this scheme; everything else is bookkeeping.

The server is generating the nonce (rather than the device signing its own timestamp)
because it makes the device's clock irrelevant and makes replay impossible by
construction, at the cost of one extra round trip on token issue and nothing else.
"""

import datetime
import logging
import secrets

import asyncpg
from fastapi import APIRouter, Depends, HTTPException, Request, status
from pydantic import BaseModel, Field

from app.auth.keys import b64u_encode, device_id_for, parse_public_key, verify_signature
from app.auth.tokens import issue_token
from app.config import Settings, get_settings

logger = logging.getLogger("kinex.api.auth")

router = APIRouter(prefix="/auth", tags=["auth"])

NONCE_BYTES = 32

_INVALID_PUBLIC_KEY = HTTPException(
    status_code=status.HTTP_400_BAD_REQUEST,
    detail="public_key must be a 32-byte Ed25519 key, base64url, unpadded",
)

# One error for every redemption failure: unknown nonce, expired nonce, nonce belonging
# to a different key, bad signature. Distinguishing them would tell an attacker which
# half of the guess was right.
_CHALLENGE_FAILED = HTTPException(
    status_code=status.HTTP_401_UNAUTHORIZED,
    detail="challenge could not be redeemed",
)


class ChallengeRequest(BaseModel):
    public_key: str = Field(max_length=64)


class ChallengeResponse(BaseModel):
    nonce: str
    expires_at: datetime.datetime


class TokenRequest(BaseModel):
    public_key: str = Field(max_length=64)
    nonce: str = Field(max_length=64)
    signature: str = Field(max_length=128)


class TokenResponse(BaseModel):
    access_token: str
    token_type: str = "Bearer"
    expires_in: int
    device_id: str


@router.post("/challenge", response_model=ChallengeResponse)
async def create_challenge(
    body: ChallengeRequest,
    request: Request,
    settings: Settings = Depends(get_settings),
) -> ChallengeResponse:
    # Validate before touching the database, so a malformed key cannot fill the table.
    try:
        parse_public_key(body.public_key)
    except ValueError:
        raise _INVALID_PUBLIC_KEY

    nonce = b64u_encode(secrets.token_bytes(NONCE_BYTES))
    expires_at = datetime.datetime.now(datetime.UTC) + datetime.timedelta(
        seconds=settings.auth_challenge_ttl_seconds
    )

    pool: asyncpg.Pool = request.app.state.pool
    async with pool.acquire() as connection:
        async with connection.transaction():
            # Sweep first. Unconsumed challenges are otherwise an unbounded slow leak:
            # every abandoned handshake leaves a row nothing will ever delete.
            await connection.execute(
                "DELETE FROM auth_challenges WHERE expires_at <= now()"
            )
            await connection.execute(
                "INSERT INTO auth_challenges (nonce, public_key, expires_at)"
                " VALUES ($1, $2, $3)",
                nonce,
                body.public_key,
                expires_at,
            )

    return ChallengeResponse(nonce=nonce, expires_at=expires_at)


@router.post("/token", response_model=TokenResponse)
async def redeem_challenge(
    body: TokenRequest,
    request: Request,
    settings: Settings = Depends(get_settings),
) -> TokenResponse:
    pool: asyncpg.Pool = request.app.state.pool

    async with pool.acquire() as connection:
        async with connection.transaction():
            # Atomic consume. The DELETE takes a row lock, so of two concurrent requests
            # carrying the same nonce exactly one finds a row — the other sees none and
            # gets the same 401 as a forgery. Matching public_key here is what stops a
            # nonce issued to one device being spent by another.
            consumed = await connection.fetchval(
                "DELETE FROM auth_challenges"
                " WHERE nonce = $1 AND public_key = $2 AND expires_at > now()"
                " RETURNING nonce",
                body.nonce,
                body.public_key,
            )
            if consumed is None:
                raise _CHALLENGE_FAILED

            # Verified after the consume, inside the same transaction, so a bad signature
            # rolls the DELETE back and the nonce survives to its 60-second expiry. That
            # is deliberate: burning it would let anyone who observed a nonce deny the
            # real device its handshake, and the forgery it would prevent is a 2^512
            # search.
            if not verify_signature(body.public_key, body.nonce, body.signature):
                raise _CHALLENGE_FAILED

            device_id = device_id_for(body.public_key)
            await connection.execute(
                "INSERT INTO devices (device_id, public_key) VALUES ($1, $2)"
                " ON CONFLICT (device_id) DO UPDATE SET last_seen_at = now()",
                device_id,
                body.public_key,
            )

    token, expires_in = issue_token(device_id, settings)
    logger.info("token issued", extra={"device_id": device_id})
    return TokenResponse(access_token=token, expires_in=expires_in, device_id=device_id)
