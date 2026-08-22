"""The ACRA endpoint — the one anonymous write.

The governing rule under test: a crash report must never be refused for a reason that
has nothing to do with the crash. Not for missing auth, not for a stale token, not for
being unparseable.
"""

import json

import pytest

from app.config import get_settings


async def test_an_unauthenticated_report_is_accepted_and_unattributed(client, db):
    response = await client.post("/crashes", json={"stack": "boom", "app": "1.0"})

    assert response.status_code == 202
    row = await db.fetchrow("SELECT device_id, payload FROM crashes")
    assert row["device_id"] is None
    assert json.loads(row["payload"])["stack"] == "boom"


async def test_a_report_from_a_known_device_is_attributed(client, device, db):
    await client.post("/crashes", json={"stack": "boom"}, headers=device.auth)

    assert await db.fetchval("SELECT device_id FROM crashes") == device.device_id


@pytest.mark.parametrize(
    "header",
    ["Bearer garbage", "Bearer not.a.jwt", "Basic abc", "Bearer "],
    ids=["garbage", "malformed-jwt", "wrong-scheme", "empty"],
)
async def test_a_bad_token_is_treated_as_absent_never_as_an_error(client, db, header):
    """Never 401 a crash report. The report lands; it is simply unattributed."""
    response = await client.post(
        "/crashes", json={"stack": "boom"}, headers={"Authorization": header}
    )

    assert response.status_code == 202, f"{header!r} was rejected"
    assert await db.fetchval("SELECT device_id FROM crashes") is None


async def test_an_expired_token_is_treated_as_absent(client, db):
    import datetime

    import jwt

    from app.auth.tokens import ALGORITHM, ISSUER

    past = datetime.datetime.now(datetime.UTC) - datetime.timedelta(hours=2)
    expired = jwt.encode(
        {"iss": ISSUER, "sub": "some-device", "iat": past, "exp": past + datetime.timedelta(hours=1)},
        get_settings().jwt_secret,
        algorithm=ALGORITHM,
    )

    response = await client.post(
        "/crashes", json={"stack": "boom"}, headers={"Authorization": f"Bearer {expired}"}
    )

    assert response.status_code == 202
    assert await db.fetchval("SELECT device_id FROM crashes") is None


async def test_the_payload_is_stored_whole_and_unparsed(client, db):
    """ACRA's shape changes between versions, so nothing here may depend on it."""
    report = {
        "REPORT_ID": "abc-123",
        "STACK_TRACE": "java.lang.IllegalStateException",
        "CUSTOM_DATA": {"exercise_id": 0, "nested": [1, 2, {"deep": True}]},
        "a field invented next year": "must survive",
    }

    await client.post("/crashes", json=report)

    assert json.loads(await db.fetchval("SELECT payload FROM crashes")) == report


async def test_a_body_that_is_not_json_is_kept_rather_than_rejected(client, db):
    response = await client.post(
        "/crashes",
        content=b"\x80\x81 not json at all",
        headers={"Content-Type": "application/json"},
    )

    assert response.status_code == 202
    assert "not json at all" in json.loads(await db.fetchval("SELECT payload FROM crashes"))["_raw"]


async def test_an_oversized_report_is_refused(client, db):
    limit = get_settings().crash_max_body_bytes
    oversized = json.dumps({"stack": "x" * (limit + 1024)}).encode()

    response = await client.post(
        "/crashes", content=oversized, headers={"Content-Type": "application/json"}
    )

    assert response.status_code == 413
    assert await db.fetchval("SELECT count(*) FROM crashes") == 0


async def test_an_oversized_report_lying_about_its_length_is_still_refused(client, db):
    """Content-Length is a claim. The stream is counted as it arrives."""
    limit = get_settings().crash_max_body_bytes

    async def chunks():
        for _ in range((limit // 1024) + 4):
            yield b"x" * 1024

    response = await client.post(
        "/crashes", content=chunks(), headers={"Content-Type": "application/json"}
    )

    assert response.status_code == 413
    assert await db.fetchval("SELECT count(*) FROM crashes") == 0


async def test_reports_are_rate_limited_per_source(client, db):
    settings = get_settings()
    allowed = settings.crash_rate_limit_requests

    for i in range(allowed):
        assert (await client.post("/crashes", json={"n": i})).status_code == 202, i

    blocked = await client.post("/crashes", json={"n": "one too many"})
    assert blocked.status_code == 429
    assert "Retry-After" in blocked.headers
    assert await db.fetchval("SELECT count(*) FROM crashes") == allowed


async def test_an_empty_body_is_rejected(client):
    response = await client.post(
        "/crashes", content=b"", headers={"Content-Type": "application/json"}
    )
    assert response.status_code == 400
