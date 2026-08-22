package com.kinex.sync

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.kinex.BuildConfig
import com.kinex.data.SessionRepository
import com.kinex.data.SessionWithReps
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Uploads every set the backend has not acknowledged, then records that it has.
 *
 * The device is the source of truth and this is a one-way push: nothing here reads history back
 * down. Restoring a history onto a new phone is a separate endpoint the backend does not have
 * yet — see "GET /sessions does not return reps" in the backend design doc.
 *
 * **Ordering is what makes a crash survivable.** The row is written locally first, with its
 * uid; the upload happens second; `syncedAt` is written third, only after the server has
 * answered about those exact sets. Every crash point in that sequence leaves the set either
 * unsynced (so the next run re-sends it, and the backend recognises the uid and does nothing)
 * or synced-and-recorded. There is no ordering where a set is lost, and none where a retry
 * becomes a second workout.
 */
class SessionSyncWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val repository = SessionRepository.get(applicationContext)
        val identity = DeviceIdentity.get(applicationContext)
        val api = KineXApi(BuildConfig.API_BASE_URL)
        val authenticator = Authenticator(api, identity)

        return try {
            var batches = 0
            while (true) {
                val batch = repository.unsyncedSessions(BATCH_SIZE)
                if (batch.isEmpty()) break

                val payload = batch.map(SessionWithReps::toPayload)
                val response = try {
                    api.ingest(authenticator.bearerToken(), payload)
                } catch (rejected: ApiException) {
                    // A token we thought was valid but the server did not. One retry with a
                    // fresh one; if that also 401s, the signature is wrong rather than the
                    // token stale, and it falls through to the handler below.
                    if (rejected.status != 401) throw rejected
                    api.ingest(authenticator.refreshedToken(), payload)
                }

                // `already_present` is an acknowledgement, not a failure — it is the
                // idempotency key doing its job on a retry, and those sets are on the server
                // just as much as the newly created ones are.
                val acknowledged = response.created + response.alreadyPresent
                if (acknowledged.isEmpty()) {
                    // The server answered about none of them, which is not a shape the ingest
                    // endpoint can produce. Stopping rather than looping is the point: the
                    // query would return the same rows forever.
                    Log.w(TAG, "server acknowledged none of ${batch.size} sessions")
                    return Result.retry()
                }
                repository.markSynced(acknowledged, System.currentTimeMillis())
                batches++
            }
            if (batches > 0) Log.i(TAG, "synced $batches batch(es)")
            Result.success()
        } catch (failure: ApiException) {
            if (failure.status in 400..499) {
                // Permanent as sent. Retrying a 422 forever would burn battery for nothing, so
                // this stops — and the count on the Settings screen is what makes that visible
                // rather than silent, because nothing else would say the backlog has stalled.
                Log.e(TAG, "refused, giving up until the next trigger: ${failure.message}")
                Result.failure()
            } else {
                Log.w(TAG, "server error, will retry: ${failure.message}")
                Result.retry()
            }
        } catch (failure: IOException) {
            // Unreachable host, dropped connection, timeout. All of these are "later".
            Log.w(TAG, "network failure, will retry: ${failure.message}")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "KineXSync"

        /** The backend caps a batch at 200 sessions; the loop above covers any backlog past it. */
        private const val BATCH_SIZE = 200

        private const val UNIQUE_NAME = "kinex-session-sync"

        /**
         * Asks for a sync. Cheap and idempotent — call it whenever a set is written and on app
         * start; WorkManager does the waiting.
         *
         * **`UNMETERED`, not `CONNECTED`.** "When on Wi-Fi" as a person means it, and as this
         * app should mean it, is "not out of my data allowance" — which is what unmetered
         * expresses. It also correctly refuses a metered Wi-Fi hotspot, where `CONNECTED` would
         * happily spend a tethered phone's data.
         *
         * **`KEEP`, not `REPLACE`.** A pending run already drains *everything* unsynced at the
         * moment it executes, so it subsumes any request made while it waits — replacing it
         * would gain nothing and would cancel an upload already in flight. The one gap is a set
         * finished while a run is executing: `KEEP` drops that request, and the set waits for
         * the next trigger. Since every following set and every app start is a trigger, the
         * worst case is the last set of a workout syncing at the next launch.
         */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequest.Builder(SessionSyncWorker::class.java)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}

/**
 * Room's shape to the wire's.
 *
 * [SessionWithReps.session]'s stored `repCount` is sent rather than `reps.size`, which the
 * server checks the two against each other. They are equal by construction — the repository
 * writes both in one transaction and nothing ever deletes a rep — so a mismatch means the local
 * row is damaged, and a 422 naming the field is the correct outcome. Quietly sending
 * `reps.size` would sync a row whose count disagrees with the history screen's.
 */
private fun SessionWithReps.toPayload(): SessionPayload = SessionPayload(
    clientSessionId = session.uid,
    exerciseId = session.exerciseId,
    startedAtMs = session.startedAtMs,
    durationMs = session.durationMs,
    repCount = session.repCount,
    reps = reps.map { RepPayload(it.repIndex, it.peakProgress, it.violationMask) },
)
