package com.kinex.sync

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import com.kinex.BuildConfig
import com.kinex.data.RecordedRep
import com.kinex.data.SessionRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * The sync path, end to end, against a backend that is actually running.
 *
 * **This test requires `docker compose up -d` in `backend/` and an emulator**, and it reaches
 * the host through `10.0.2.2`, which is the emulator's alias for the host loopback. It is an
 * integration test in the honest sense — if the backend is down it fails, and that is correct,
 * because there is nothing here worth asserting against a fake. Every property this covers is a
 * property of two implementations agreeing: the signature the server verifies, the field names
 * it deserialises, the unique constraint that makes a retry a no-op. A mock would be a second
 * copy of the server written by the person whose assumptions are being checked, and it would
 * agree with them — which is the same argument the backend design doc makes for testing against a
 * real Postgres rather than a fake one.
 *
 * The device id and the uploaded uid are logged under [TAG] on purpose, so that a run can be
 * cross-checked in `psql` from the host rather than only believed.
 *
 * **Two of these tests read the build constant and one reads the setting, which is worth knowing
 * before debugging a split result.** The client built below is pinned to `BuildConfig`, so it is
 * not at the mercy of whatever address was last typed into Settings on this device.
 * [theWorkerUploadsARealSetAndRecordsThatItDid] cannot be: it runs the real worker, and the real
 * worker resolves `AppSettings.apiBaseUrl` — which is exactly what makes it the end-to-end test.
 * If that one alone fails to connect, the address in Settings is the first thing to look at.
 */
@RunWith(AndroidJUnit4::class)
class SessionSyncTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    // The build constant, deliberately, where the app itself now reads `AppSettings.apiBaseUrl`.
    // A test that resolved the address the same way the app does would depend on whatever was
    // last typed into Settings on this device, and would fail for a reason with nothing to do
    // with the code under test.
    private val api = KineXApi { BuildConfig.API_BASE_URL }
    private val identity = DeviceIdentity.get(context)
    private val authenticator = Authenticator(api, identity)

    @Test
    fun theHandshakeYieldsATokenAndTheDeviceIdDerivedFromOurOwnKey() = runBlocking<Unit> {
        val token = authenticator.bearerToken()
        assertTrue("a JWT has three dot-separated parts", token.count { it == '.' } == 2)

        val serverDeviceId = identity.deviceId()
        assertNotNull("the token response carried no device_id", serverDeviceId)
        // The strongest single assertion available without a database: the server's device id
        // is a SHA-256 of the public key, so this agreeing with what DeviceKeys computes proves
        // the server parsed the same 32 bytes we sent — no padding drift, no DER wrapper.
        assertEquals(identity.keys().deviceId, serverDeviceId)

        Log.i(TAG, "device_id=$serverDeviceId")
    }

    @Test
    fun aSessionIsCreatedOnceAndAlreadyPresentOnEveryRetry() = runBlocking<Unit> {
        val uid = UUID.randomUUID().toString()
        val payload = listOf(
            SessionPayload(
                clientSessionId = uid,
                exerciseId = 0,
                startedAtMs = System.currentTimeMillis(),
                durationMs = 42_000,
                repCount = 2,
                // 1.0204 is squat_8rep's real unclamped peak. It goes into a Postgres `real`
                // and has to come back out intact — the column has no CHECK constraint
                // precisely so an above-1.0 value survives.
                reps = listOf(
                    RepPayload(repIndex = 1, peakProgress = 1.0204f, violationMask = 0),
                    RepPayload(repIndex = 2, peakProgress = 0.81f, violationMask = 1),
                ),
            )
        )

        val first = api.ingest(authenticator.bearerToken(), payload)
        assertEquals("the first upload should create it", listOf(uid), first.created)
        assertEquals(emptyList<String>(), first.alreadyPresent)

        // Byte-identical retry. This is the case a sync that dies after writing produces, and
        // the whole reason the identifier is a stored UUID rather than Room's rowid.
        val second = api.ingest(authenticator.bearerToken(), payload)
        assertEquals("a retry must not create a second workout", emptyList<String>(), second.created)
        assertEquals(listOf(uid), second.alreadyPresent)

        Log.i(TAG, "uploaded uid=$uid device_id=${identity.deviceId()}")
    }

    @Test
    fun theWorkerUploadsARealSetAndRecordsThatItDid() = runBlocking<Unit> {
        val repository = SessionRepository.get(context)
        val sessionId = repository.saveSet(
            exerciseId = 0,
            startedAtMs = System.currentTimeMillis(),
            durationMs = 30_000,
            reps = listOf(
                RecordedRep(peakProgress = 1.0174f, violationMask = 0),
                RecordedRep(peakProgress = 0.79f, violationMask = 1),
            ),
        )
        assertNotNull("the set was not written locally", sessionId)

        val before = repository.session(sessionId!!).first()!!
        assertNotNull("no uid was minted at insert", before.uid)
        assertEquals("a fresh set must not claim to be synced", null, before.syncedAt)

        // Runs doWork() directly, so this is the real worker with the real repository and the
        // real client — only WorkManager's scheduling is stood in for.
        val result = TestListenableWorkerBuilder<SessionSyncWorker>(context).build().doWork()
        assertEquals(androidx.work.ListenableWorker.Result.success(), result)

        val after = repository.session(sessionId).first()!!
        // The assertion is on the database rather than on the response, and deliberately: what
        // has to be true afterwards is that this device will not send the set again. A run that
        // uploaded successfully but failed to record it would re-send forever.
        assertNotNull("the set is still unsynced after a successful run", after.syncedAt)

        Log.i(TAG, "worker synced uid=${after.uid} device_id=${identity.deviceId()}")
    }

    @Test
    fun theEnqueuedWorkWaitsForAnUnmeteredNetwork() {
        SessionSyncWorker.enqueue(context)
        val info = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork("kinex-session-sync")
            .get()
            .single()
        // "When on Wi-Fi" as the ask meant it. CONNECTED would spend a phone's data allowance
        // uploading a workout, and would also happily use a metered tethered hotspot.
        assertEquals(NetworkType.UNMETERED, info.constraints.requiredNetworkType)
    }

    private companion object {
        const val TAG = "KineXSyncTest"
    }
}
