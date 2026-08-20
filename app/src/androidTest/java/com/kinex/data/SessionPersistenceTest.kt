package com.kinex.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 6's verify step, as far as a test can carry it: **an offline workout survives a
 * restart**.
 *
 * The restart is the point, and it is why nothing here uses an in-memory database. A set is
 * written, the database is closed exactly as it would be if the process died, and a second
 * database object is opened over the same file — which is what the next launch does. An
 * in-memory store would pass every assertion below while proving nothing at all.
 *
 * The half this cannot reach is the camera: whether a real set of reps ends and is written is
 * a person standing in front of a phone, not a test.
 */
@RunWith(AndroidJUnit4::class)
class SessionPersistenceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var database: KineXDatabase? = null

    @Before
    fun deleteAnyPreviousRun() {
        context.deleteDatabase(DATABASE_NAME)
    }

    @After
    fun close() {
        database?.close()
        context.deleteDatabase(DATABASE_NAME)
    }

    private fun open(): KineXDatabase =
        Room.databaseBuilder(context, KineXDatabase::class.java, DATABASE_NAME).build()
            .also { database = it }

    private fun reopen(): KineXDatabase {
        database?.close()
        return open()
    }

    @Test
    fun aSavedSetSurvivesTheDatabaseBeingClosedAndReopened() = runBlocking {
        val written = SessionRepository(open().sessionDao()).saveSet(
            exerciseId = 0,
            startedAtMs = 1_760_000_000_000L,
            durationMs = 64_000L,
            reps = listOf(
                RecordedRep(peakProgress = 1.0204f, violationMask = 2),
                RecordedRep(peakProgress = 0.81f, violationMask = 1),
                RecordedRep(peakProgress = 0.9973f, violationMask = 0),
            ),
        )
        assertNotNull("saveSet refused a set with three reps in it", written)

        val reread = SessionRepository(reopen().sessionDao())
        val sessions = reread.sessions().first()
        assertEquals("the set did not survive the reopen", 1, sessions.size)

        val session = sessions.first()
        assertEquals(0, session.exerciseId)
        assertEquals(1_760_000_000_000L, session.startedAtMs)
        assertEquals(64_000L, session.durationMs)
        assertEquals(3, session.repCount)

        val reps = reread.reps(session.id).first()
        assertEquals(3, reps.size)
        assertEquals(listOf(1, 2, 3), reps.map { it.repIndex })
        assertEquals(listOf(2, 1, 0), reps.map { it.violationMask })

        // The reason slot [6] was added to the JNI contract. A rep that went 2% past its
        // target has to come back out of storage saying so, not flattened to 1.00.
        assertEquals(1.0204f, reps[0].peakProgress, 1e-6f)
        assertTrue(
            "the deepest rep came back clamped, so the record cannot tell 1.02 from 1.00",
            reps[0].peakProgress > 1.0f,
        )
        assertEquals(0.81f, reps[1].peakProgress, 1e-6f)
    }

    /**
     * Somebody opening the camera screen, watching the skeleton and leaving should not appear
     * in their own history. The refusal lives in the repository rather than the UI so that no
     * caller can write one by accident.
     */
    @Test
    fun aSetWithNoRepsIsNotWritten() = runBlocking {
        val repository = SessionRepository(open().sessionDao())
        val written = repository.saveSet(
            exerciseId = 3,
            startedAtMs = 1_760_000_000_000L,
            durationMs = 0L,
            reps = emptyList(),
        )
        assertEquals("an empty set was written", null, written)
        assertEquals(0, repository.sessions().first().size)
    }

    /**
     * Sets are read newest first, which is the only order a history screen wants. Written out
     * of order on purpose — if this passed on insertion order it would be asserting nothing.
     */
    @Test
    fun setsComeBackNewestFirst() = runBlocking {
        val repository = SessionRepository(open().sessionDao())
        val middle = 1_760_000_000_000L
        for (startedAtMs in listOf(middle, middle + 60_000L, middle - 60_000L)) {
            repository.saveSet(
                exerciseId = 0,
                startedAtMs = startedAtMs,
                durationMs = 1_000L,
                reps = listOf(RecordedRep(1.0f, 0)),
            )
        }

        val ordered = SessionRepository(reopen().sessionDao()).sessions().first()
        assertEquals(
            listOf(middle + 60_000L, middle, middle - 60_000L),
            ordered.map { it.startedAtMs },
        )
    }

    /**
     * Reps belong to their own set and to no other. A missing `WHERE sessionId` would pass
     * every other test in this file and hand one set's detail screen the whole table.
     */
    @Test
    fun repsAreScopedToTheirOwnSet() = runBlocking {
        val repository = SessionRepository(open().sessionDao())
        val first = repository.saveSet(0, 1_760_000_000_000L, 1_000L, listOf(RecordedRep(1.0f, 0)))
        val second = repository.saveSet(
            exerciseId = 5,
            startedAtMs = 1_760_000_060_000L,
            durationMs = 2_000L,
            reps = listOf(RecordedRep(0.5f, 1), RecordedRep(0.6f, 1)),
        )

        val reread = SessionRepository(reopen().sessionDao())
        assertEquals(1, reread.reps(first!!).first().size)
        assertEquals(2, reread.reps(second!!).first().size)
    }

    private companion object {
        /** Not "kinex.db": this test deletes its database, and that one holds real workouts. */
        const val DATABASE_NAME = "session-persistence-test.db"
    }
}
