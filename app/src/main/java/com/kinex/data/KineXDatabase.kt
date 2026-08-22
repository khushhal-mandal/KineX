package com.kinex.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The on-device store. Sessions are written here first and synced afterwards; nothing in this
 * package talks to the network, and the sync worker reads it like any other caller.
 *
 * `exportSchema` is on and `app/schemas/` is committed. Each version's JSON is what the next
 * migration gets written and auto-verified against; without it the only record of a schema's
 * shape is whatever shipped inside an APK.
 *
 * No `fallbackToDestructiveMigration`. A schema change with no migration should fail loudly
 * in development rather than silently delete a user's workout history on upgrade — which is
 * exactly the thing Phase 6's verify step says must survive.
 */
@Database(entities = [SessionEntity::class, RepEntity::class], version = 2, exportSchema = true)
abstract class KineXDatabase : RoomDatabase() {

    abstract fun sessionDao(): SessionDao

    companion object {
        @Volatile
        private var instance: KineXDatabase? = null

        /**
         * v1 → v2: the session identifier the backend needs, and a sync marker.
         *
         * **Three statements rather than a table rebuild, and the reason is the foreign key.**
         * The textbook way to add a `NOT NULL UNIQUE` column with a backfill is to build a new
         * table, copy into it, drop the old one and rename — but `reps` references
         * `sessions(id)`, and the SQLite procedure for that requires `PRAGMA foreign_keys=off`,
         * which is a **no-op inside a transaction**. Room runs migrations inside one. So the
         * rebuild would either run with foreign keys live, taking the reps out with the dropped
         * table, or need the pragma to work where it cannot. Adding the column in place avoids
         * the question entirely.
         *
         * The `DEFAULT ''` is what makes `ADD COLUMN ... NOT NULL` legal on a populated table;
         * every row is overwritten by the `UPDATE` on the next line before anything can read
         * one. Room tolerates a database-side default the entity does not declare, so it does
         * not appear in the schema JSON and does not affect the identity hash.
         *
         * **UUIDs are minted in SQL, so no Kotlin runs between the two statements.** SQLite
         * treats `random()` and `randomblob()` as non-deterministic and evaluates them per row
         * rather than hoisting them out of the UPDATE — verified on 500 rows before this was
         * written, which is worth doing because the failure mode if it were hoisted is one
         * identifier shared by every historical session, and the unique index would then fail
         * the migration in a way that reads as data corruption. The layout is canonical v4:
         * version nibble `4`, variant nibble from `89ab`, lowercase, hyphenated.
         *
         * `syncedAt` is left NULL for every existing row, which is true — nothing has ever
         * synced — and means the first sync uploads the whole existing history.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN uid TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE sessions ADD COLUMN syncedAt INTEGER")
                db.execSQL(
                    """
                    UPDATE sessions SET uid = lower(
                        hex(randomblob(4)) || '-' ||
                        hex(randomblob(2)) || '-' ||
                        '4' || substr(hex(randomblob(2)), 2) || '-' ||
                        substr('89ab', 1 + (random() & 3), 1) ||
                            substr(hex(randomblob(2)), 2) || '-' ||
                        hex(randomblob(6))
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_sessions_uid ON sessions (uid)"
                )
            }
        }

        /**
         * One database per process. SQLite tolerates several connections; Room's own advice
         * is a single instance, and two would each hold their own write lock and cache.
         */
        fun get(context: Context): KineXDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    KineXDatabase::class.java,
                    "kinex.db",
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
    }
}
