package com.kinex.sync

import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The `/coach/chat` wire contract, against a response the running backend actually sent.
 *
 * A field name that does not match is invisible until a device is in front of you: the request
 * goes out fine, and the failure is a `SerializationException` decoding the reply — after the
 * question has been sent, the quota spent and the person has waited. That is the cheapest bug
 * in this file to introduce and one of the more annoying to see, so it is pinned here instead.
 *
 * [RECORDED_RESPONSE] is a verbatim capture from `POST /coach/chat` on 23 Aug 2026 — the
 * laptop backend, the real synced device (`CIt-EqLk…`, 4 sessions), Groq's `openai/gpt-oss-20b`.
 * Do not tidy it. Its value is that nobody wrote it by hand, so it carries the fields this
 * client does not read and the shape of the ones it does.
 *
 * This is not the `auth_v1.json` arrangement and does not pretend to be. That vector is
 * committed on the backend and read by both halves, so the two languages check each other.
 * This is a recording: it catches a rename on *this* side, and would go stale silently if the
 * backend renamed a field without anyone re-capturing it.
 */
class CoachWireTest {

    @Test
    fun `a real reply decodes, and the fields this client reads survive`() {
        val decoded = KineXApi.JSON.decodeFromString<CoachChatResponse>(RECORDED_RESPONSE)

        assertEquals("openai/gpt-oss-20b", decoded.model)
        // The snake_case name is the whole point of the assertion: `sessionsConsidered` would
        // decode to 0 rather than fail if the @SerialName were wrong, and 0 renders on screen
        // as "from 0 synced sessions" under an answer that is in fact grounded in four.
        assertEquals(4, decoded.grounding.sessionsConsidered)
        assertEquals(true, decoded.reply.startsWith("Your squat volume"))
    }

    @Test
    fun `fields the client does not read are dropped rather than refused`() {
        // `grounding.exercises`, `grounding.summaries` and everything inside them. The backend
        // returns them and this client has no use for them; ignoreUnknownKeys is what keeps
        // that from being a crash, and it is also what lets the backend add a field.
        val decoded = KineXApi.JSON.decodeFromString<CoachChatResponse>(RECORDED_RESPONSE)
        assertEquals(4, decoded.grounding.sessionsConsidered)
    }

    @Test
    fun `the request carries the one field the endpoint validates`() {
        assertEquals(
            """{"message":"How has my squat volume changed?"}""",
            KineXApi.JSON.encodeToString(CoachChatRequest("How has my squat volume changed?")),
        )
    }

    private companion object {
        const val RECORDED_RESPONSE = """
{
    "reply": "Your squat volume hasn’t changed at all—both the all‑time and last‑90‑days totals are 8 reps, all from the same four sessions on 2026‑08‑22.  \nThe only exercise you’re doing is the squat, so that’s clearly your focus right now.",
    "model": "openai/gpt-oss-20b",
    "grounding": {
        "sessions_considered": 4,
        "exercises": [
            "Squat"
        ],
        "summaries": [
            {
                "period_start_ms": 1787385073153,
                "period_end_ms": 1787395948195,
                "session_count": 4,
                "distance": 0.237230357463445
            }
        ]
    }
}
"""
    }
}
