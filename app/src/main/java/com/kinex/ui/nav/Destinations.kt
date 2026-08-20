package com.kinex.ui.nav

import kotlinx.serialization.Serializable

/**
 * Every destination in the app, as navigation-compose's type-safe routes.
 *
 * `@Serializable` is not decoration. Navigation reads a route's arguments back out through
 * the generated serializer, so the annotation and the `kotlin.plugin.serialization` plugin in
 * `app/build.gradle.kts` are what make `entry.toRoute<SessionDetail>()` give a `Long` rather
 * than a `String?` that somebody has to parse and null-check at every call site.
 *
 * Note what the argument-carrying routes carry: an **id**, never an entity. A route survives
 * process death by being written to a Bundle and read back, so anything in it has to be a
 * value the store can be re-queried with. The history screen used to hold a `SessionEntity`
 * in composable state and hand it to the detail view directly; that could not be a route, and
 * it also meant the detail view rendered a snapshot of a row rather than the row.
 */

/** Start destination. Exercise grid, this week's totals, recent sessions. */
@Serializable
data object Home

/** Bottom-nav tab: every past set, newest first. */
@Serializable
data object History

/** Bottom-nav tab: camera default, TTS placeholder, about. */
@Serializable
data object Settings

/**
 * The live camera screen, running [exerciseId] — a native config table id, the same integer
 * that crosses JNI to `NativeEngine.create` and gets stored on a session row.
 *
 * The exercise is part of the route rather than picked inside the screen because Home's grid
 * is now the way in, and a workout that started as a lunge should still be a lunge after the
 * process is killed and the back stack restored.
 */
@Serializable
data class Workout(val exerciseId: Int)

/**
 * What the set you just finished came to. Reached from [Workout] when a set is written, and
 * **replaces** it on the back stack rather than stacking on top — see the navigate call in
 * `MainActivity`, where that is argued.
 */
@Serializable
data class SetSummary(val sessionId: Long)

/** One past set and its reps, reached from [History] or from Home's recent list. */
@Serializable
data class SessionDetail(val sessionId: Long)
