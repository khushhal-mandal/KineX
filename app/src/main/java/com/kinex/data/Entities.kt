package com.kinex.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

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
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val exerciseId: Int,
    val startedAtMs: Long,
    val durationMs: Long,
    val repCount: Int,
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
