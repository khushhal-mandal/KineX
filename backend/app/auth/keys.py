"""Ed25519 keys, the signed challenge message, and base64url encoding.

This module is one half of a contract the Kotlin side has to match byte for byte. The
other half is written down in "The auth contract" in the root design doc, and
tests/vectors/auth_v1.json is the executable version of it.

Three things here are easy to get subtly wrong across two languages, so they are stated
once and not restated anywhere else in code:

1. **base64url, no padding**, everywhere — on the wire and in the database. Python's
   `urlsafe_b64encode` pads with `=` and Kotlin's `Base64.URL_SAFE` does too unless told
   `NO_PADDING`. A padded/unpadded mismatch is a bad afternoon, so encoding is funnelled
   through the two helpers below and nothing else calls base64 directly.
2. **Raw key bytes**, not DER or PEM: 32 for a public key, 64 for a signature.
3. **The signed message is the domain-separated string**, not the bare nonce — see
   `auth_message`.
"""

import base64
import hashlib

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey

PUBLIC_KEY_BYTES = 32
SIGNATURE_BYTES = 64

# Bumping this string is how the message format is versioned. Both sides must agree.
AUTH_DOMAIN = "kinex-auth-v1:"


def b64u_encode(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")


def b64u_decode(encoded: str, *, expected_length: int) -> bytes:
    """Strict: rejects anything outside the base64url alphabet, and rejects a value that
    decodes to the wrong length. Lenient decoding here would let two spellings of one key
    become two devices."""
    if not isinstance(encoded, str):
        raise ValueError("expected a base64url string")
    padded = encoded + "=" * (-len(encoded) % 4)
    try:
        raw = base64.b64decode(padded, altchars=b"-_", validate=True)
    except Exception as exc:
        raise ValueError("not valid unpadded base64url") from exc
    if len(raw) != expected_length:
        raise ValueError(f"expected {expected_length} bytes, got {len(raw)}")
    return raw


def parse_public_key(encoded: str) -> Ed25519PublicKey:
    """Decode and validate. Raises ValueError on anything that is not a usable key."""
    raw = b64u_decode(encoded, expected_length=PUBLIC_KEY_BYTES)
    try:
        return Ed25519PublicKey.from_public_bytes(raw)
    except Exception as exc:
        raise ValueError("not a valid Ed25519 public key") from exc


def device_id_for(public_key_encoded: str) -> str:
    """`base64url(sha256(raw public key))`, unpadded — 43 characters.

    Derived rather than random so that registering the same key twice is the same device,
    which is what makes a recovery-phrase restore land on the existing account instead of
    creating a second one. Hashed rather than using the key itself so that a device_id in
    a log line or a session row is not key material.
    """
    raw = b64u_decode(public_key_encoded, expected_length=PUBLIC_KEY_BYTES)
    return b64u_encode(hashlib.sha256(raw).digest())


def auth_message(nonce: str) -> bytes:
    """The exact bytes the device signs: ASCII of `"kinex-auth-v1:" + nonce`.

    The nonce is concatenated in its base64url *text* form, not decoded first — there is
    then no question on either side about whether it was decoded, and the whole message is
    printable ASCII, which makes a mismatch visible in a log rather than a hex diff.

    Domain separation matters because the server chooses the nonce. Signing bare
    server-chosen bytes turns the device into a signing oracle: whatever else that
    signature might one day be valid for, this prefix makes it valid only for KineX auth.
    """
    return (AUTH_DOMAIN + nonce).encode("ascii")


def verify_signature(public_key_encoded: str, nonce: str, signature_encoded: str) -> bool:
    """False on every failure, never an exception — callers turn this into one 401 and
    must not be able to tell a malformed key from a wrong signature."""
    try:
        public_key = parse_public_key(public_key_encoded)
        signature = b64u_decode(signature_encoded, expected_length=SIGNATURE_BYTES)
    except ValueError:
        return False
    try:
        public_key.verify(signature, auth_message(nonce))
    except InvalidSignature:
        return False
    return True
