"""The committed auth test vector.

`tests/vectors/auth_v1.json` is the contract between this server and the Kotlin client,
in executable form. The Kotlin side has to reproduce every field in it; when it does, its
first real handshake works, and when it does not, the mismatch is visible as a diff on a
known value instead of as a 401 with nothing to inspect.

These tests are the server's half of that. They also guard the vector itself: if someone
changes the domain string, the encoding, or the seed slice, one of these goes red rather
than the Kotlin side finding out in Phase 9.
"""

import hashlib
import json
import pathlib
import unicodedata

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat

from app.auth.keys import AUTH_DOMAIN, auth_message, b64u_encode, device_id_for, verify_signature
from tests.conftest import authenticate

VECTOR = json.loads((pathlib.Path(__file__).parent / "vectors" / "auth_v1.json").read_text())

# The published BIP-39 test vector for all-zero entropy with passphrase "TREZOR". Present
# so the seed derivation is checked against the spec rather than against our own output —
# a self-consistent but wrong PBKDF2 would otherwise pass every test in this file.
BIP39_PUBLISHED_TREZOR_SEED = (
    "c55257c360c07c72029aebc1b53c05ed0362ada38ead3e3e9efa3708e5349553"
    "1f09a6987599d18264c1e1c92f2cf141630c7a3c4ab7c81b2f001698e7463b04"
)


def bip39_seed(phrase: str, passphrase: str = "") -> bytes:
    return hashlib.pbkdf2_hmac(
        "sha512",
        unicodedata.normalize("NFKD", phrase).encode(),
        unicodedata.normalize("NFKD", "mnemonic" + passphrase).encode(),
        2048,
        dklen=64,
    )


def test_seed_derivation_matches_the_published_bip39_vector():
    assert bip39_seed(VECTOR["recovery_phrase"], "TREZOR").hex() == BIP39_PUBLISHED_TREZOR_SEED


def test_the_vectors_seed_is_reproducible():
    seed = bip39_seed(VECTOR["recovery_phrase"], VECTOR["bip39_passphrase"])
    assert seed.hex() == VECTOR["bip39_seed_hex"]
    # The seed slice, stated as its own assertion because it is the single most likely
    # place for a Kotlin implementation to diverge — by reaching for BIP-32 instead.
    assert seed[:32].hex() == VECTOR["ed25519_seed_hex"]


def test_the_vectors_key_and_device_id_are_reproducible():
    seed = bytes.fromhex(VECTOR["ed25519_seed_hex"])
    private = Ed25519PrivateKey.from_private_bytes(seed)
    public_key = b64u_encode(private.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw))

    assert public_key == VECTOR["public_key_b64url"]
    assert device_id_for(public_key) == VECTOR["device_id"]
    # base64url with no padding, on the wire and in the database.
    assert "=" not in public_key
    assert "+" not in public_key and "/" not in public_key


def test_the_vectors_signature_is_reproducible():
    private = Ed25519PrivateKey.from_private_bytes(bytes.fromhex(VECTOR["ed25519_seed_hex"]))
    nonce = VECTOR["nonce_b64url"]

    assert auth_message(nonce).decode("ascii") == VECTOR["signed_message_ascii"]
    assert VECTOR["signed_message_ascii"].startswith(AUTH_DOMAIN)
    # Ed25519 signing is deterministic, which is what makes this assertable at all.
    assert b64u_encode(private.sign(auth_message(nonce))) == VECTOR["signature_b64url"]


def test_the_vectors_signature_verifies_through_the_server_path():
    assert verify_signature(
        VECTOR["public_key_b64url"], VECTOR["nonce_b64url"], VECTOR["signature_b64url"]
    )


async def test_the_vectors_key_authenticates_against_the_running_api(client, db):
    """End to end: the committed key completes a real handshake and lands as the
    committed device_id. This is the assertion the Kotlin side is really matching."""
    private = Ed25519PrivateKey.from_private_bytes(bytes.fromhex(VECTOR["ed25519_seed_hex"]))

    device = await authenticate(client, private)

    assert device.public_key == VECTOR["public_key_b64url"]
    assert device.device_id == VECTOR["device_id"]
    assert await db.fetchval("SELECT device_id FROM devices") == VECTOR["device_id"]
