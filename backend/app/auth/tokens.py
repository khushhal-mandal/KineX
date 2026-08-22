"""JWT issue and verify.

HS256 with a server-held secret. The device never verifies a token — it only carries one
back — so an asymmetric signing key would buy nothing and cost a key to distribute.
"""

import datetime

import jwt

from app.config import Settings

ISSUER = "kinex"
ALGORITHM = "HS256"


def issue_token(device_id: str, settings: Settings) -> tuple[str, int]:
    """Returns the token and its lifetime in seconds."""
    now = datetime.datetime.now(datetime.UTC)
    expires_in = settings.jwt_ttl_seconds
    claims = {
        "iss": ISSUER,
        "sub": device_id,
        "iat": now,
        "exp": now + datetime.timedelta(seconds=expires_in),
    }
    return jwt.encode(claims, settings.jwt_secret, algorithm=ALGORITHM), expires_in


def device_id_from_token(token: str, settings: Settings) -> str | None:
    """The device_id a valid token names, or None for any token that is not valid.

    `algorithms` is pinned to one value deliberately: accepting a list the attacker can
    choose from is the classic JWT confusion bug, and `none` is in that family.
    """
    try:
        claims = jwt.decode(
            token,
            settings.jwt_secret,
            algorithms=[ALGORITHM],
            issuer=ISSUER,
            options={"require": ["exp", "iat", "sub", "iss"]},
        )
    except jwt.InvalidTokenError:
        return None
    subject = claims.get("sub")
    return subject if isinstance(subject, str) and subject else None
