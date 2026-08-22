package com.kinex.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

/**
 * One completed set.
 *
 * Two clocks, each used for what it is good at. [startedAtMs] is wall clock, because the only
 * thing it is for is putting a date on a history row. [durationMs] is measured on the frame
 * clock — MediaPipe's monotonic timestamps — because a wall clock can step sideways mid-set
 * and produce a negative duration.
 *
 * [durationMs] runs from the first counted rep to the last, not from when the screen opened
 * to when the set was saved. Otherwise every set would include the rest that ended it, and a
 * set of ten reps would be indistinguishable from a set of ten reps followed by a phone call.
 * A one-rep set therefore reads 0 ms, which is true.
 *
 * [exerciseId] is the native config table's id, not this build's enum ordinal. Ids are
 * appended and never renumbered precisely so a row written today still means the same
 * exercise after another five land.
 */
@Entity(tableName = "sessions", indices = [Index(value = ["uid"], unique = true)])
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /**
     * The identifier the backend knows this set by — half of `(device_id, client_session_id)`,
     * which is what makes an ingest idempotent. A v4 UUID, canonical lowercase hyphenated form.
     *
     * **Minted when the row is inserted, and never again.** Not at sync time: a sync that
     * writes to the server and then dies before recording that it did has to present the same
     * value on the retry, or the retry is a second workout. Storing it is what makes the
     * identifier survive the crash, so it belongs to the row from the moment the row exists.
     *
     * **[id] cannot do this job**, which is why the column is here at all. A rowid is unique
     * only within one database file: `pm clear` wiped this database on 19 Aug 2026 and a
     * rebuilt one restarts AUTOINCREMENT at 1, so its new sessions would collide with
     * already-synced ones under the same device and be dropped as duplicate retries — silently
     * losing a *new* workout. A UUID is the only value a wiped device cannot accidentally
     * re-mint.
     *
     * Canonical form is stored rather than any spelling `uuid` would accept. Postgres folds
     * case and braces on the way in, so a non-canonical value would still collide correctly —
     * but then a request log and a `psql` session would show different strings for one row,
     * and that difference is only ever discovered while debugging something else.
     */
    val uid: String,
    val exerciseId: Int,
    val startedAtMs: Long,
    val durationMs: Long,
    val repCount: Int,
    /**
     * Wall clock at the moment the backend acknowledged this set, or null while it has not.
     *
     * Null is the whole query: the sync worker uploads `WHERE syncedAt IS NULL`. It is set only
     * after a response, so a run that dies mid-upload leaves the row unsynced and the next run
     * re-sends it — which the backend absorbs, because that is what `(device_id, uid)` is for.
     *
     * Wall clock rather than the frame clock, because this timestamp is about the network and
     * has no relationship to anything MediaPipe measured.
     */
    val syncedAt: Long? = null,
)

/**
 * One rep of one set.
 *
 * [peakProgress] is slot [6] — the highest normalized progress the rep reached, unclamped, so
 * a rep that went 2% past its target is distinguishable from one that just met it. Below 1.0
 * is a rep that stopped short; the depth-miss bit in [violationMask] is the same fact stated
 * as a verdict rather than a number.
 *
 * [violationMask] is stored raw rather than as decoded labels. The bits are the engine's
 * vocabulary and a label is one build's rendering of them — storing the rendering would mean
 * a rule renamed in Phase 8 rewrites history.
 *
 * Deleting a session takes its reps with it (`CASCADE`). Nothing deletes sessions yet; the
 * rule is here so that when something does, it cannot leave orphans behind.
 */
/**
 * The result of an aggregate query, not a table.
 *
 * No `@Entity`, no id, nothing in the schema JSON — Room maps a query's columns onto it by
 * name, which is why the field names here have to match the `AS` aliases in
 * [SessionDao.statsSince] exactly.
 */
data class TrainingTotals(
    val setCount: Int,
    val repCount: Int,
)

/**
 * A set with the reps that belong to it, which is the shape the backend ingests.
 *
 * Not a table either — Room fills [reps] with a second query keyed on the sessions it just
 * read, which is why the DAO method returning this is `@Transaction`. One extra query per
 * batch, rather than one per session, is the reason to state the relation rather than loop.
 */
data class SessionWithReps(
    @Embedded val session: SessionEntity,
    @Relation(parentColumn = "id", entityColumn = "sessionId")
    val reps: List<RepEntity>,
)

@Entity(
    tableName = "reps",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("sessionId")],
)
data class RepEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    /** 1-based position within the set, so a detail screen can say "rep 3" without arithmetic. */
    val repIndex: Int,
    val peakProgress: Float,
    val violationMask: Int,
)
