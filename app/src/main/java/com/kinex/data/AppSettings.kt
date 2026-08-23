package com.kinex.data

import android.content.Context
import androidx.camera.core.CameraSelector

/**
 * The handful of preferences the settings screen owns.
 *
 * SharedPreferences rather than DataStore, and that is a dependency decision rather than a
 * technical one: DataStore is the modern answer and it is also another artifact to add for
 * two booleans and an int. The design doc's rule is to ask before adding a dependency, and this
 * did not need one. If preferences ever grow to the point of wanting a Flow per key, that is
 * the moment to make the case for DataStore.
 *
 * Reads are synchronous against an in-memory map after the first load, so calling these from
 * a composable is not a disk hit per frame. Writes use `apply` — losing the last write to a
 * process kill costs the user one toggle, and blocking the main thread to prevent that costs
 * every user every time.
 */
class AppSettings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("kinex.settings", Context.MODE_PRIVATE)

    /**
     * Which lens a workout opens on. A `CameraSelector.LENS_FACING_*` constant, stored raw
     * because that is what CameraX takes and translating it twice would only add a table to
     * disagree with itself.
     *
     * Back by default: four of the ten exercises are side-view movements you set the phone
     * down for, and the back camera is the better sensor on essentially every device. The two
     * front-view rows are what this setting exists for.
     */
    var defaultLensFacing: Int
        get() = prefs.getInt(KEY_LENS_FACING, CameraSelector.LENS_FACING_BACK)
        set(value) = prefs.edit().putInt(KEY_LENS_FACING, value).apply()

    /**
     * Whether spoken rep counts and form cues are wanted.
     *
     * Read by `WorkoutViewModel`, once, when the workout screen's ViewModel is built: false
     * means no `TextToSpeech` engine is constructed at all. Changing it therefore applies to
     * the next workout rather than to one already running, which costs nothing — Settings is
     * not reachable without leaving the workout screen.
     *
     * On by default. Somebody who has put the phone down two metres away to film a squat
     * cannot read the count, which is the entire reason Phase 4's verify step says "audible
     * and legible" rather than just legible.
     */
    var speakCues: Boolean
        get() = prefs.getBoolean(KEY_SPEAK_CUES, true)
        set(value) = prefs.edit().putBoolean(KEY_SPEAK_CUES, value).apply()

    /**
     * Whether the workout screen shows its engine readout — FSM state, primary angle, live
     * progress, calibrated start angle, and what the last counted rep scored.
     *
     * Off by default, because none of it means anything to somebody doing squats and all of
     * it competes with the rep count for the same glance. It is not debug-build-only, though:
     * these numbers are how a new exercise's `depth_pass` gets chosen and how a set that
     * counts nothing gets diagnosed, and that has to be possible on a release build in a gym
     * rather than only on a developer's desk.
     */
    var showEngineReadout: Boolean
        get() = prefs.getBoolean(KEY_ENGINE_READOUT, false)
        set(value) = prefs.edit().putBoolean(KEY_ENGINE_READOUT, value).apply()

    private companion object {
        const val KEY_LENS_FACING = "default_lens_facing"
        const val KEY_SPEAK_CUES = "speak_cues"
        const val KEY_ENGINE_READOUT = "show_engine_readout"
    }
}
