"""Session ingest and read-back.

The three properties the session brief named, plus the boundaries around them:
a duplicate batch is a no-op, a partial retry completes, and one device's JWT reaches
none of another device's data.
"""

import json
import uuid

import pytest


def a_session(reps: int = 3, **overrides) -> dict:
    """A plausible squat set. peak_progress values are real ones from squat_8rep."""
    peaks = [1.0174, 1.0099, 1.0085, 1.0078, 1.0141, 1.0014, 1.0204, 0.9973]
    body = {
        "client_session_id": str(uuid.uuid4()),
        "exercise_id": 0,
        "started_at_ms": 1755700000000,
        "duration_ms": 42000,
        "rep_count": reps,
        "reps": [
            {"rep_index": i, "peak_progress": peaks[i % len(peaks)], "violation_mask": 0}
            for i in range(reps)
        ],
    }
    body.update(overrides)
    return body


async def test_a_batch_is_stored_with_its_reps(client, device, db):
    batch = [a_session(reps=3), a_session(reps=2)]
    response = await client.post("/sessions", json={"sessions": batch}, headers=device.auth)

    assert response.status_code == 200
    assert set(response.json()["created"]) == {s["client_session_id"] for s in batch}
    assert response.json()["already_present"] == []
    assert await db.fetchval("SELECT count(*) FROM sessions") == 2
    assert await db.fetchval("SELECT count(*) FROM reps") == 5


async def test_a_duplicate_batch_is_a_no_op(client, device, db):
    """Re-sending an identical batch must write nothing and say so."""
    batch = [a_session(reps=3), a_session(reps=2)]
    payload = {"sessions": batch}

    first = await client.post("/sessions", json=payload, headers=device.auth)
    assert len(first.json()["created"]) == 2

    second = await client.post("/sessions", json=payload, headers=device.auth)
    assert second.status_code == 200
    assert second.json()["created"] == []
    assert set(second.json()["already_present"]) == {s["client_session_id"] for s in batch}

    assert await db.fetchval("SELECT count(*) FROM sessions") == 2
    assert await db.fetchval("SELECT count(*) FROM reps") == 5


async def test_a_partial_retry_completes(client, device, db):
    """The interrupted-sync case: the session row landed but not all of its reps.

    Simulated by deleting reps after a successful write, which is indistinguishable from
    a first attempt that died between the two inserts. The retry has to fill the gap —
    a "skip anything already present" shortcut would strand those reps forever.
    """
    session = a_session(reps=5)
    await client.post("/sessions", json={"sessions": [session]}, headers=device.auth)

    await db.execute("DELETE FROM reps WHERE rep_index >= 2")
    assert await db.fetchval("SELECT count(*) FROM reps") == 2

    retry = await client.post("/sessions", json={"sessions": [session]}, headers=device.auth)

    assert retry.status_code == 200
    assert retry.json()["already_present"] == [session["client_session_id"]]
    assert retry.json()["created"] == []
    assert await db.fetchval("SELECT count(*) FROM reps") == 5
    assert await db.fetchval("SELECT count(*) FROM sessions") == 1

    indices = [r["rep_index"] for r in await db.fetch("SELECT rep_index FROM reps ORDER BY 1")]
    assert indices == [0, 1, 2, 3, 4]


async def test_a_mixed_batch_reports_each_session_correctly(client, device):
    old = a_session()
    await client.post("/sessions", json={"sessions": [old]}, headers=device.auth)

    new = a_session()
    response = await client.post(
        "/sessions", json={"sessions": [old, new]}, headers=device.auth
    )

    assert response.json()["created"] == [new["client_session_id"]]
    assert response.json()["already_present"] == [old["client_session_id"]]


async def test_another_devices_jwt_cannot_read_these_sessions(client, device, other_device):
    await client.post(
        "/sessions", json={"sessions": [a_session(), a_session()]}, headers=device.auth
    )

    mine = await client.get("/sessions", headers=device.auth)
    theirs = await client.get("/sessions", headers=other_device.auth)

    assert len(mine.json()["sessions"]) == 2
    assert theirs.json()["sessions"] == []


async def test_another_devices_jwt_cannot_write_into_these_sessions(
    client, device, other_device, db
):
    """The same client_session_id under a different token is a *different* session, not
    an overwrite of this device's row and not a rejected duplicate."""
    session = a_session(reps=2)
    await client.post("/sessions", json={"sessions": [session]}, headers=device.auth)

    intruder = await client.post(
        "/sessions", json={"sessions": [session]}, headers=other_device.auth
    )

    # Accepted, because it is legitimately that device's own new session.
    assert intruder.json()["created"] == [session["client_session_id"]]

    owners = await db.fetch(
        "SELECT device_id FROM sessions WHERE client_session_id = $1",
        uuid.UUID(session["client_session_id"]),
    )
    assert {row["device_id"] for row in owners} == {device.device_id, other_device.device_id}
    assert len(owners) == 2

    # And each still sees exactly one.
    assert len((await client.get("/sessions", headers=device.auth)).json()["sessions"]) == 1
    assert len((await client.get("/sessions", headers=other_device.auth)).json()["sessions"]) == 1


async def test_device_id_in_the_body_is_ignored(client, device, other_device, db):
    """There is no device_id field on the request model, so one supplied in the body must
    be dropped by validation rather than trusted."""
    session = a_session()
    session["device_id"] = other_device.device_id

    await client.post("/sessions", json={"sessions": [session]}, headers=device.auth)

    owner = await db.fetchval("SELECT device_id FROM sessions")
    assert owner == device.device_id


async def test_the_unclamped_peak_survives_the_round_trip(client, device, db):
    """The whole reason peak_progress has no CHECK. 38.75 was measured on a device."""
    session = a_session(reps=1)
    session["reps"] = [{"rep_index": 0, "peak_progress": 38.75, "violation_mask": 3}]

    await client.post("/sessions", json={"sessions": [session]}, headers=device.auth)

    stored = await db.fetchval("SELECT peak_progress FROM reps")
    assert stored == pytest.approx(38.75)


async def test_pagination_walks_every_session_exactly_once(client, device):
    sessions = [a_session(reps=1, started_at_ms=1755700000000 + i) for i in range(25)]
    await client.post("/sessions", json={"sessions": sessions}, headers=device.auth)

    seen: list[str] = []
    cursor = None
    for _ in range(10):
        url = f"/sessions?limit=10{f'&cursor={cursor}' if cursor else ''}"
        page = (await client.get(url, headers=device.auth)).json()
        seen.extend(s["client_session_id"] for s in page["sessions"])
        cursor = page["next_cursor"]
        if cursor is None:
            break

    assert cursor is None
    assert len(seen) == 25
    assert len(set(seen)) == 25, "a session appeared on two pages"
    assert seen == sorted(
        seen,
        key=lambda cid: next(
            -s["started_at_ms"] for s in sessions if s["client_session_id"] == cid
        ),
    )


async def test_a_malformed_cursor_is_a_400_not_a_500(client, device):
    response = await client.get("/sessions?cursor=nonsense!!", headers=device.auth)
    assert response.status_code == 400


async def test_inconsistent_batches_are_rejected(client, device, db):
    bad_cases = {
        "rep_count disagrees with the reps sent": a_session(reps=3) | {"rep_count": 4},
        "duplicate rep_index": a_session(reps=1)
        | {
            "rep_count": 2,
            "reps": [
                {"rep_index": 0, "peak_progress": 1.0, "violation_mask": 0},
                {"rep_index": 0, "peak_progress": 1.0, "violation_mask": 0},
            ],
        },
    }
    for label, session in bad_cases.items():
        response = await client.post(
            "/sessions", json={"sessions": [session]}, headers=device.auth
        )
        assert response.status_code == 422, label

    duplicate = a_session()
    response = await client.post(
        "/sessions", json={"sessions": [duplicate, duplicate]}, headers=device.auth
    )
    assert response.status_code == 422, "duplicate client_session_id within one batch"

    assert await db.fetchval("SELECT count(*) FROM sessions") == 0


async def test_a_non_finite_peak_is_rejected(client, device, db):
    """`Infinity` has to be posted as a raw body: httpx refuses to encode it, because it
    is not legal JSON. A lenient client serializer can still emit it, and Python's
    json.loads accepts it, so it does reach the model — where it must be refused.

    Rejecting NaN and infinity is not clamping. peak_progress is deliberately unbounded
    above; it just has to be a number, because `real` will store a NaN that no later
    query can interpret.
    """
    for literal in ("Infinity", "-Infinity", "NaN"):
        session = a_session(reps=1)
        session["reps"] = [{"rep_index": 0, "peak_progress": 0, "violation_mask": 0}]
        body = json.dumps({"sessions": [session]}).replace(
            '"peak_progress": 0', f'"peak_progress": {literal}'
        )
        response = await client.post(
            "/sessions",
            content=body,
            headers={**device.auth, "Content-Type": "application/json"},
        )
        assert response.status_code == 422, f"{literal} was accepted"

    assert await db.fetchval("SELECT count(*) FROM sessions") == 0
