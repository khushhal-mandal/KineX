package com.kinex.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The four backend calls this app makes, over `HttpURLConnection`.
 *
 * **No Retrofit and no OkHttp**, and that is a deliberate reading of the working agreement's
 * rule about dependencies the root design doc does not name. There are four endpoints, all
 * JSON, none streaming, none needing interceptors or a connection pool that outlives a
 * fifteen-second sync. What a client library would buy here is about forty lines.
 *
 * **Field names are snake_case, spelled out with `@SerialName` on every field.** The
 * alternative — one `JsonNamingStrategy.SnakeCase` on the `Json` instance — is fewer
 * characters and hides the wire contract behind a global rule. The backend design doc's "The wire
 * format" section puts the rename boundary here on purpose, so this is the file somebody
 * diffs against `app/api/sessions.py` when a sync starts 422ing; the names need to be legible
 * in it.
 *
 * [baseUrl] is a provider rather than a string because the address is a setting, not a build
 * constant. It is called once per request, so an address changed in Settings applies to the
 * next call without rebuilding anything that holds a client — which is what lets a long-lived
 * `CoachViewModel` go on constructing its `KineXApi` exactly once.
 */
internal class KineXApi(private val baseUrl: () -> String) {

    suspend fun challenge(publicKey: String): ChallengeResponse =
        post("/auth/challenge", ChallengeRequest(publicKey))

    suspend fun token(publicKey: String, nonce: String, signature: String): TokenResponse =
        post("/auth/token", TokenRequest(publicKey, nonce, signature))

    suspend fun ingest(bearerToken: String, sessions: List<SessionPayload>): IngestResponse =
        post("/sessions", IngestRequest(sessions), bearerToken)

    /**
     * One coaching question, answered cold.
     *
     * The request carries the question and nothing else. There is no conversation-history
     * field to fill in — the backend retrieves fresh against the full question every time,
     * which is what keeps the answer grounded in this device's sessions rather than in what
     * was said three turns ago. See `backend/app/api/coach.py`.
     */
    suspend fun coachChat(bearerToken: String, message: String): CoachChatResponse =
        post("/coach/chat", CoachChatRequest(message), bearerToken)

    private suspend inline fun <reified B, reified R> post(
        path: String,
        body: B,
        bearerToken: String? = null,
    ): R = withContext(Dispatchers.IO) {
        // Resolved per request, and inside the IO block on purpose: the address lives in
        // `AppSettings` now, so a laptop that moved is a field to retype rather than a rebuild,
        // and the first read of it is a disk hit that has no business on the main thread.
        val connection = (URL(baseUrl() + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            bearerToken?.let { setRequestProperty("Authorization", "Bearer $it") }
        }
        try {
            connection.outputStream.use { it.write(JSON.encodeToString(body).toByteArray()) }
            val status = connection.responseCode
            if (status !in 200..299) {
                // errorStream, not inputStream: reading the latter on a 4xx throws, and the
                // body is where FastAPI puts the reason a request was refused.
                val detail = connection.errorStream?.bufferedReader()?.use { it.readText() }
                throw ApiException(status, detail.orEmpty().take(MAX_ERROR_CHARS))
            }
            JSON.decodeFromString<R>(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 30_000

        /** Enough of an error body to identify a 422's field, not enough to fill a log. */
        private const val MAX_ERROR_CHARS = 512

        /**
         * `ignoreUnknownKeys` so a field added to a response by a newer backend is not a client
         * crash. The reverse — a field this client needs going missing — still fails, which is
         * the right way round.
         *
         * Not private, so `CoachWireTest` can decode a recorded server response through the
         * same configuration this file uses rather than through a copy of it. A test that
         * builds its own `Json` proves the test's decoder works.
         */
        val JSON = Json { ignoreUnknownKeys = true }
    }
}

/**
 * A non-2xx response. [status] is carried because the sync worker's whole retry decision is
 * made from it: 401 means re-authenticate and try once more, 5xx means come back later, and
 * any other 4xx means this request will never succeed as sent.
 */
internal class ApiException(val status: Int, val detail: String) :
    IOException("HTTP $status${if (detail.isBlank()) "" else ": $detail"}")

@Serializable
internal data class ChallengeRequest(
    @SerialName("public_key") val publicKey: String,
)

@Serializable
internal data class ChallengeResponse(
    val nonce: String,
    @SerialName("expires_at") val expiresAt: String,
)

@Serializable
internal data class TokenRequest(
    @SerialName("public_key") val publicKey: String,
    val nonce: String,
    val signature: String,
)

@Serializable
internal data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String = "Bearer",
    @SerialName("expires_in") val expiresIn: Long,
    /**
     * The server's `base64url(sha256(public key))`. The device could derive this itself and
     * [DeviceKeys] does, for the vector check — but the value carried around at runtime is the
     * one the server answered with, so there is one authority for it rather than two.
     */
    @SerialName("device_id") val deviceId: String,
)

@Serializable
internal data class RepPayload(
    @SerialName("rep_index") val repIndex: Int,
    /** Unclamped, and stays that way: a real 38.75 has been recorded. */
    @SerialName("peak_progress") val peakProgress: Float,
    @SerialName("violation_mask") val violationMask: Int,
)

/**
 * One set.
 *
 * There is no `device_id` field here and there must never be one. The server takes the device
 * from the JWT and drops anything a body claims — see the note at the top of
 * `backend/app/api/sessions.py`. A field here would be silently ignored, which is worse than
 * absent because it would look like it worked.
 */
@Serializable
internal data class SessionPayload(
    @SerialName("client_session_id") val clientSessionId: String,
    @SerialName("exercise_id") val exerciseId: Int,
    @SerialName("started_at_ms") val startedAtMs: Long,
    @SerialName("duration_ms") val durationMs: Long,
    @SerialName("rep_count") val repCount: Int,
    val reps: List<RepPayload>,
)

@Serializable
internal data class IngestRequest(val sessions: List<SessionPayload>)

/**
 * Both lists are acknowledgements. `already_present` is not a failure — it is the idempotency
 * key doing its job on a retry — so the worker marks the union of the two as synced.
 */
@Serializable
internal data class IngestResponse(
    val created: List<String>,
    @SerialName("already_present") val alreadyPresent: List<String>,
)

/**
 * A coaching question.
 *
 * The backend caps this at 1000 characters and refuses a blank one with a 422. Neither bound
 * is repeated here as a constant — the input field enforces the same limits for the sake of
 * the person typing, and a duplicated cap that drifts from the server's is worse than none.
 */
@Serializable
internal data class CoachChatRequest(val message: String)

@Serializable
internal data class CoachChatResponse(
    val reply: String,
    /**
     * Whichever model answered, as the backend's provider names it — `openai/gpt-oss-20b`
     * today. It is `"none"` for the reply the backend writes itself when a device has no
     * sessions to ground an answer in, and that case is not attributed to a model on screen.
     */
    val model: String,
    val grounding: GroundingPayload,
)

/**
 * What the answer was built from.
 *
 * The backend returns more of this than is decoded here — the retrieved summaries and their
 * distances — and `ignoreUnknownKeys` drops them. The session count is the part worth showing:
 * it is the difference between an answer about this athlete and an answer about nothing, and
 * the coach's whole claim is that it is reading real sets.
 */
@Serializable
internal data class GroundingPayload(
    @SerialName("sessions_considered") val sessionsConsidered: Int,
)
