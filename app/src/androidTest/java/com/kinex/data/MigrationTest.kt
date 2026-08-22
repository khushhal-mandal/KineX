package com.kinex.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The v1 → v2 migration, run against a database with rows in it.
 *
 * An empty-table migration proves almost nothing here: the whole difficulty of this one is the
 * backfill, and a backfill over zero rows always succeeds. So every test below starts by
 * building a real v1 database — [MigrationTestHelper] does that from the committed `1.json` —
 * filling it, and migrating that.
 *
 * `runMigrationsAndValidate` does a second job worth naming: it compares the migrated schema
 * against the committed `2.json`, so a migration that produces *nearly* the right table fails
 * here rather than at the user's next app launch, which is where Room would otherwise notice.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        KineXDatabase::class.java,
    )

    /**
     * Deliberately larger than it needs to be to test distinctness.
     *
     * The failure this guards is SQLite hoisting `randomblob()` out of the UPDATE and giving
     * every row the same identifier. Across five rows that is a coincidence away from passing;
     * across two hundred it is not. It also makes the symptom unambiguous — a hoisted value
     * would leave exactly one distinct uid, not a near miss.
     */
    private val sessionCount = 200

    @Test
    fun everyExistingSessionGetsADistinctUid() {
        helper.createDatabase(TEST_DB, 1).use { db -> db.seedVersionOne() }

        migrated().use { db ->
            db.query("SELECT COUNT(*), COUNT(DISTINCT uid), COUNT(uid) FROM sessions").use {
                it.moveToFirst()
                assertEquals("rows lost in the migration", sessionCount, it.getInt(0))
                assertEquals("uids are not distinct", sessionCount, it.getInt(1))
                // COUNT(column) skips NULLs, so this reading disagreeing with the row count is
                // how a nullable-column mistake would show up.
                assertEquals("a uid came out null", sessionCount, it.getInt(2))
            }
            db.query("SELECT COUNT(*) FROM sessions WHERE uid = ''").use {
                it.moveToFirst()
                assertEquals("the ADD COLUMN default survived the backfill", 0, it.getInt(0))
            }
        }
    }

    @Test
    fun theBackfilledUidsAreCanonicalVersionFourUuids() {
        helper.createDatabase(TEST_DB, 1).use { db -> db.seedVersionOne() }

        migrated().use { db ->
            db.query("SELECT uid FROM sessions").use { cursor ->
                while (cursor.moveToNext()) {
                    val uid = cursor.getString(0)
                    // Postgres `uuid` would accept other spellings, so this is not about the
                    // server refusing it — it is about a request log and a psql session showing
                    // the same string for the same row.
                    assertTrue("not a canonical v4 uuid: $uid", CANONICAL_V4.matches(uid))
                }
            }
        }
    }

    @Test
    fun everythingElseSurvives() {
        helper.createDatabase(TEST_DB, 1).use { db -> db.seedVersionOne() }

        migrated().use { db ->
            // The reps are the reason this migration adds a column in place rather than
            // rebuilding the table: they hang off sessions(id) by a foreign key, and a rebuild
            // done wrong takes them with it. Two reps went in; two come out.
            db.query("SELECT COUNT(*) FROM reps").use {
                it.moveToFirst()
                assertEquals("reps were lost", 2, it.getInt(0))
            }
            db.query("SELECT exerciseId, startedAtMs, durationMs, repCount FROM sessions WHERE id = 1").use {
                it.moveToFirst()
                assertEquals(0, it.getInt(0))
                assertEquals(1_000L, it.getLong(1))
                assertEquals(5_000L, it.getLong(2))
                assertEquals(8, it.getInt(3))
            }
            db.query("SELECT COUNT(*) FROM sessions WHERE syncedAt IS NULL").use {
                it.moveToFirst()
                // True rather than merely convenient: nothing has ever synced, so the first
                // run after this migration uploads the whole existing history.
                assertEquals("a pre-existing set claims to be synced", sessionCount, it.getInt(0))
            }
        }
    }

    @Test
    fun theUniqueIndexIsRealAndRejectsADuplicate() {
        helper.createDatabase(TEST_DB, 1).use { db -> db.seedVersionOne() }

        migrated().use { db ->
            val existing = db.query("SELECT uid FROM sessions LIMIT 1").use {
                it.moveToFirst()
                it.getString(0)
            }
            // A unique index that exists in the schema JSON but was never created would let
            // this through, and the symptom would arrive much later as a backend collision.
            val failure = runCatching {
                db.execSQL(
                    "INSERT INTO sessions (uid, exerciseId, startedAtMs, durationMs, repCount)" +
                        " VALUES (?, 0, 1, 1, 1)",
                    arrayOf(existing),
                )
            }.exceptionOrNull()
            assertTrue("a duplicate uid was accepted", failure != null)
        }
    }

    @Test
    fun migratingAnEmptyDatabaseIsAlsoFine() {
        // The ordinary upgrade path for anyone whose history was wiped — which is this
        // project's own device, after pm clear on 19 Aug 2026. An UPDATE over no rows and a
        // unique index over no rows both have to be non-events.
        helper.createDatabase(TEST_DB, 1).close()
        migrated().use { db ->
            db.query("SELECT COUNT(*) FROM sessions").use {
                it.moveToFirst()
                assertEquals(0, it.getInt(0))
            }
        }
    }

    private fun migrated(): SupportSQLiteDatabase =
        helper.runMigrationsAndValidate(TEST_DB, 2, true, KineXDatabase.MIGRATION_1_2)

    /** A v1 database as Phase 6 left it: sessions with reps, no uid, no sync marker. */
    private fun SupportSQLiteDatabase.seedVersionOne() {
        beginTransaction()
        try {
            repeat(sessionCount) { index ->
                execSQL(
                    "INSERT INTO sessions (exerciseId, startedAtMs, durationMs, repCount)" +
                        " VALUES (?, ?, ?, ?)",
                    arrayOf<Any>(index % 10, 1_000L + index, 5_000L, 8),
                )
            }
            // Real values off squat_8rep, including an unclamped peak past 1.0 — the number
            // The design doc says must survive every layer without being flattened.
            execSQL(
                "INSERT INTO reps (sessionId, repIndex, peakProgress, violationMask)" +
                    " VALUES (1, 1, 1.0204, 0), (1, 2, 0.81, 1)"
            )
            setTransactionSuccessful()
        } finally {
            endTransaction()
        }
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
        val CANONICAL_V4 =
            Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
    }
}
