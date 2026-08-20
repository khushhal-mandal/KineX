package com.kinex.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The on-device store. Everything here is local and stays local — Phase 8 onwards adds a
 * backend, and nothing in this package knows about it.
 *
 * `exportSchema` is on and `app/schemas/` is committed. Version 1's JSON is what a later
 * migration gets written and auto-verified against; without it the only record of this
 * schema's shape is whatever shipped inside an APK.
 *
 * No `fallbackToDestructiveMigration`. A schema change with no migration should fail loudly
 * in development rather than silently delete a user's workout history on upgrade — which is
 * exactly the thing Phase 6's verify step says must survive.
 */
@Database(entities = [SessionEntity::class, RepEntity::class], version = 1, exportSchema = true)
abstract class KineXDatabase : RoomDatabase() {

    abstract fun sessionDao(): SessionDao

    companion object {
        @Volatile
        private var instance: KineXDatabase? = null

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
                ).build().also { instance = it }
            }
    }
}
