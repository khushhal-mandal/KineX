package com.kinex.audio

import com.kinex.pose.Violation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a counted rep says, and in what order.
 *
 * A plain JVM test because [cueUtterances] is deliberately the pure half of the speaker: the
 * `TextToSpeech` half needs a device with voice data installed, and the part worth pinning is
 * the decision about what text comes out and when — not that Android's synthesiser works.
 */
class CueUtterancesTest {

    @Test
    fun `a clean rep says its number and nothing else`() {
        assertEquals(listOf("7"), cueUtterances(7, emptyList()))
    }

    @Test
    fun `the number comes first, then one correction per flagged rule`() {
        assertEquals(
            listOf("3", "Go deeper", "Chest up"),
            cueUtterances(3, listOf(Violation.DEPTH_MISS, Violation.TORSO_LEAN)),
        )
    }

    /**
     * The ordering is the requirement, not an accident of how the list is built. The count is
     * what the athlete is tracking, and a cue in front of it makes them wait to find out where
     * they are — so a rep must never speak advice before its number.
     */
    @Test
    fun `the count is always the first thing spoken`() {
        for (violations in listOf(
            emptyList(),
            listOf(Violation.TORSO_LEAN),
            listOf(Violation.DEPTH_MISS, Violation.TORSO_LEAN),
        )) {
            assertEquals("12", cueUtterances(12, violations).first())
        }
    }

    /**
     * The brief's own line: "DEPTH" is a tag, not something to say out loud. This asserts the
     * two vocabularies have not been collapsed into one — which is the shape the mistake would
     * take, since a single string field would work and read fine everywhere except aloud.
     */
    @Test
    fun `spoken cues are not the HUD labels`() {
        for (violation in Violation.entries) {
            assertNotEquals(violation.label, violation.cue)
            // A HUD tag is a noun for the fault; a cue is an instruction. Nothing enforces
            // that in the type, so this checks the property that follows from it: the spoken
            // form is not the tag in another case.
            assertNotEquals(violation.label.lowercase(), violation.cue.lowercase())
            assertTrue(
                "a cue arrives mid-set and cannot be re-read; keep it short",
                violation.cue.length <= 20,
            )
        }
    }

    @Test
    fun `every violation the engine can report has a cue`() {
        val spoken = cueUtterances(1, Violation.entries.toList()).drop(1)
        assertEquals(Violation.entries.size, spoken.size)
        assertTrue(spoken.none { it.isBlank() })
    }
}
