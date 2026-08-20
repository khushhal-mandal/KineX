package com.kinex.data

import android.content.Context
import kotlinx.coroutines.flow.Flow

/**
 * One rep as the camera screen accumulates it, before it has a session to belong to.
 *
 * A separate type from [RepEntity] because the screen genuinely cannot fill a [RepEntity] in:
 * the session id does not exist until the set is saved. Handing the UI a half-built entity to
 * carry around is how a zero id ends up written to a foreign key.
 */
data class RecordedRep(
    val peakProgress: Float,
    val violationMask: Int,
)

/**
 * The only way in or out of the store. The camera screen writes finished sets, the history
 * screen reads them, and neither touches Room's types beyond the entities.
 */
class SessionRepository(private val dao: SessionDao) {

    /**
     * Writes a finished set. Returns the new session's id.
     *
     * The caller decides what counts as finished; this only refuses the empty case, and it
     * refuses it here rather than in the UI so nothing can write an entry for a set with no
     * reps in it. Somebody opening the screen, watching the skeleton and walking away should
     * not appear in their own history.
     */
    suspend fun saveSet(
        exerciseId: Int,
        startedAtMs: Long,
        durationMs: Long,
        reps: List<RecordedRep>,
    ): Long? {
        if (reps.isEmpty()) return null
        val session = SessionEntity(
            exerciseId = exerciseId,
            startedAtMs = startedAtMs,
            durationMs = durationMs,
            repCount = reps.size,
        )
        // repIndex is 1-based and assigned here, so it is a property of the stored set rather
        // than of whatever order the UI happened to hand them over in.
        val rows = reps.mapIndexed { index, rep ->
            RepEntity(
                sessionId = 0,
                repIndex = index + 1,
                peakProgress = rep.peakProgress,
                violationMask = rep.violationMask,
            )
        }
        return dao.insertSet(session, rows)
    }

    fun sessions(): Flow<List<SessionEntity>> = dao.sessions()

    /** Home's recent list. [limit] is the caller's, so nothing here decides what "recent" is. */
    fun recentSessions(limit: Int): Flow<List<SessionEntity>> = dao.recentSessions(limit)

    /** Null once nothing has that id — see the DAO for why that is not a theoretical case. */
    fun session(sessionId: Long): Flow<SessionEntity?> = dao.session(sessionId)

    /**
     * Totals over everything recorded at or after [sinceMs].
     *
     * The window is the caller's to choose and this deliberately does not know what "this
     * week" means. Where a week starts is a calendar question with a locale-dependent answer,
     * and it does not belong behind a data-access method.
     */
    fun totalsSince(sinceMs: Long): Flow<TrainingTotals> = dao.statsSince(sinceMs)

    fun reps(sessionId: Long): Flow<List<RepEntity>> = dao.reps(sessionId)

    companion object {
        fun get(context: Context): SessionRepository =
            SessionRepository(KineXDatabase.get(context).sessionDao())
    }
}
