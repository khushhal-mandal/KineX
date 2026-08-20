package com.kinex.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {

    /**
     * A set and its reps, in one transaction. Both or neither: a session row without its reps
     * is a history entry that opens to an empty detail screen, and reps without a session are
     * rows nothing can ever reach.
     *
     * Returns the new session's id. The reps are given theirs here rather than by the caller,
     * because the caller does not have one until this function is halfway done.
     */
    @Transaction
    suspend fun insertSet(session: SessionEntity, reps: List<RepEntity>): Long {
        val sessionId = insertSession(session)
        insertReps(reps.map { it.copy(sessionId = sessionId) })
        return sessionId
    }

    @Insert
    suspend fun insertSession(session: SessionEntity): Long

    @Insert
    suspend fun insertReps(reps: List<RepEntity>)

    /** Newest first, which is the order a history screen wants and nothing else does. */
    @Query("SELECT * FROM sessions ORDER BY startedAtMs DESC")
    fun sessions(): Flow<List<SessionEntity>>

    /**
     * The same order, cut short for Home's recent list. A separate query rather than
     * `sessions().map { it.take(3) }`, because that would read every row a user has ever
     * recorded off disk on every emission to throw all but three away.
     */
    @Query("SELECT * FROM sessions ORDER BY startedAtMs DESC LIMIT :limit")
    fun recentSessions(limit: Int): Flow<List<SessionEntity>>

    /**
     * One session, or null once nothing has that id.
     *
     * Nullable because a route outlives the row it points at: [SessionEntity] ids travel in
     * the back stack, the back stack survives process death, and nothing stops a session
     * being gone by the time the entry is restored. Nothing deletes sessions today — that is
     * a reason for the screens to handle null gracefully, not a reason to assume non-null.
     */
    @Query("SELECT * FROM sessions WHERE id = :sessionId")
    fun session(sessionId: Long): Flow<SessionEntity?>

    /**
     * Sets and reps since [sinceMs], for Home's "this week".
     *
     * Aggregated in SQL rather than by summing a list in Kotlin: the whole point of the strip
     * is a two-integer answer, and the alternative loads the week's sessions into memory to
     * count them. `COALESCE` because `SUM` over no rows is NULL, and a week with no training
     * should read 0 rather than crash mapping null into an Int.
     */
    @Query(
        "SELECT COUNT(*) AS setCount, COALESCE(SUM(repCount), 0) AS repCount " +
            "FROM sessions WHERE startedAtMs >= :sinceMs"
    )
    fun statsSince(sinceMs: Long): Flow<TrainingTotals>

    @Query("SELECT * FROM reps WHERE sessionId = :sessionId ORDER BY repIndex ASC")
    fun reps(sessionId: Long): Flow<List<RepEntity>>
}
