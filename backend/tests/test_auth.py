"""The keypair handshake.

The threat these tests exist for: a public key is public. If presenting one were enough
to get a token, every account would belong to whoever had seen its key.
"""

import datetime

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat

from app.auth.keys import auth_message, b64u_encode, device_id_for
from tests.conftest import authenticate


def _keypair() -> tuple[Ed25519PrivateKey, str]:
    key = Ed25519PrivateKey.generate()
    return key, b64u_encode(key.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw))


async def test_handshake_issues_a_token_and_registers_the_device(client, db):
    device = await authenticate(client)

    assert device.device_id == device_id_for(device.public_key)
    row = await db.fetchrow("SELECT public_key FROM devices WHERE device_id = $1", device.device_id)
    assert row["public_key"] == device.public_key
    # No email, no password, no PII — the whole account is one key.
    columns = await db.fetch(
        "SELECT column_name FROM information_schema.columns WHERE table_name = 'devices'"
    )
    assert {c["column_name"] for c in columns} == {
        "device_id", "public_key", "created_at", "last_seen_at"
    }


async def test_the_same_key_authenticating_twice_is_one_device(client, db):
    key, _ = _keypair()
    first = await authenticate(client, key)
    second = await authenticate(client, key)

    assert first.device_id == second.device_id
    assert await db.fetchval("SELECT count(*) FROM devices") == 1


async def test_a_replayed_nonce_is_refused(client):
    """The second use of a nonce must fail even though the signature is perfectly valid."""
    key, public_key = _keypair()
    nonce = (await client.post("/auth/challenge", json={"public_key": public_key})).json()["nonce"]
    signature = b64u_encode(key.sign(auth_message(nonce)))
    body = {"public_key": public_key, "nonce": nonce, "signature": signature}

    assert (await client.post("/auth/token", json=body)).status_code == 200
    replay = await client.post("/auth/token", json=body)
    assert replay.status_code == 401


async def test_an_expired_nonce_is_refused(client, db):
    key, public_key = _keypair()
    nonce = (await client.post("/auth/challenge", json={"public_key": public_key})).json()["nonce"]

    # Age the challenge rather than sleeping out the 60s TTL.
    await db.execute(
        "UPDATE auth_challenges SET expires_at = $1 WHERE nonce = $2",
        datetime.datetime.now(datetime.UTC) - datetime.timedelta(seconds=1),
        nonce,
    )

    response = await client.post(
        "/auth/token",
        json={
            "public_key": public_key,
            "nonce": nonce,
            "signature": b64u_encode(key.sign(auth_message(nonce))),
        },
    )
    assert response.status_code == 401


async def test_a_nonce_issued_to_one_key_cannot_be_spent_by_another(client):
    """The attack this blocks: request a challenge for the victim's public key, then
    redeem it with your own key and signature."""
    victim_key, victim_public = _keypair()
    attacker_key, attacker_public = _keypair()

    nonce = (
        await client.post("/auth/challenge", json={"public_key": victim_public})
    ).json()["nonce"]

    # The attacker signs correctly — with the wrong key.
    response = await client.post(
        "/auth/token",
        json={
            "public_key": attacker_public,
            "nonce": nonce,
            "signature": b64u_encode(attacker_key.sign(auth_message(nonce))),
        },
    )
    assert response.status_code == 401

    # And cannot simply claim the victim's key either, having no private half.
    forged = await client.post(
        "/auth/token",
        json={
            "public_key": victim_public,
            "nonce": nonce,
            "signature": b64u_encode(attacker_key.sign(auth_message(nonce))),
        },
    )
    assert forged.status_code == 401
    del victim_key


async def test_a_signature_over_different_bytes_is_refused(client):
    """Specifically: signing the bare nonce instead of the domain-separated message. This
    is the mistake a Kotlin implementation is most likely to make, and it must be a 401
    rather than quietly working."""
    key, public_key = _keypair()
    nonce = (await client.post("/auth/challenge", json={"public_key": public_key})).json()["nonce"]

    for wrong_message in (nonce.encode(), b"kinex-auth-v2:" + nonce.encode(), b""):
        response = await client.post(
            "/auth/token",
            json={
                "public_key": public_key,
                "nonce": nonce,
                "signature": b64u_encode(key.sign(wrong_message)),
            },
        )
        assert response.status_code == 401, f"accepted a signature over {wrong_message!r}"


async def test_a_malformed_public_key_is_rejected_before_it_reaches_the_table(client, db):
    for bad in ("", "not-base64!!", b64u_encode(b"too short"), b64u_encode(bytes(33))):
        response = await client.post("/auth/challenge", json={"public_key": bad})
        assert response.status_code in (400, 422), bad
    assert await db.fetchval("SELECT count(*) FROM auth_challenges") == 0


async def test_expired_challenges_are_swept(client, db):
    key, public_key = _keypair()
    await client.post("/auth/challenge", json={"public_key": public_key})
    await db.execute("UPDATE auth_challenges SET expires_at = now() - interval '1 hour'")

    # Issuing any new challenge sweeps the table.
    await client.post("/auth/challenge", json={"public_key": public_key})
    assert await db.fetchval("SELECT count(*) FROM auth_challenges") == 1


async def test_endpoints_reject_absent_and_forged_tokens(client):
    assert (await client.get("/sessions")).status_code == 401
    for header in ("Bearer", "Bearer ", "Basic abc", "Bearer not.a.jwt"):
        response = await client.get("/sessions", headers={"Authorization": header})
        assert response.status_code == 401, header


async def test_a_token_signed_with_the_wrong_secret_is_refused(client):
    """Proves the JWT signature is actually checked, rather than the claims being read."""
    import jwt

    from app.auth.tokens import ALGORITHM, ISSUER

    now = datetime.datetime.now(datetime.UTC)
    forged = jwt.encode(
        {
            "iss": ISSUER,
            "sub": "any-device-id",
            "iat": now,
            "exp": now + datetime.timedelta(hours=1),
        },
        "not-the-server-secret-but-long-enough-to-avoid-a-length-warning",
        algorithm=ALGORITHM,
    )
    response = await client.get("/sessions", headers={"Authorization": f"Bearer {forged}"})
    assert response.status_code == 401
