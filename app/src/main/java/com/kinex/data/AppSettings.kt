package com.kinex.data

import android.content.Context
import androidx.camera.core.CameraSelector
import com.kinex.BuildConfig

/**
 * The handful of preferences the settings screen owns.
 *
 * SharedPreferences rather than DataStore, and that is a dependency decision rather than a
 * technical one: DataStore is the modern answer and it is also another artifact to add for
 * two booleans, an int and a string. The design doc's rule is to ask before adding a dependency,
 * and this did not need one. If preferences ever grow to the point of wanting a Flow per key,
 * that is the moment to make the case for DataStore.
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

    /**
     * Where the backend is. Origin only — scheme, host and port — because `KineXApi` appends
     * paths that already start with a slash.
     *
     * **A fallback, not a seeded value.** Nothing is stored until somebody types an address, so
     * `BuildConfig.API_BASE_URL` — `local.properties` on a debug build, the unreachable
     * `.invalid` host on a release one — keeps deciding for anyone who never opens this. Writing
     * the build's value into prefs on first launch would have frozen it there, and a later
     * `local.properties` edit would then have done nothing for a reason nobody could see.
     *
     * Read on every request rather than at construction, which is the point of it: a laptop
     * whose LAN address moves with DHCP is a field to retype, not a rebuild and a reinstall.
     */
    var apiBaseUrl: String
        get() = prefs.getString(KEY_API_BASE_URL, null) ?: BuildConfig.API_BASE_URL
        set(value) {
            // Normalized here so there is one place that does it, rather than at each of the
            // three call sites or — worse — nowhere, since `baseUrl + path` turns a trailing
            // slash a person typed into `//auth/challenge` and a 404 that names nothing.
            val normalized = value.trim().trimEnd('/')
            prefs.edit().apply {
                if (normalized.isEmpty()) remove(KEY_API_BASE_URL)
                else putString(KEY_API_BASE_URL, normalized)
            }.apply()
        }

    private companion object {
        const val KEY_LENS_FACING = "default_lens_facing"
        const val KEY_SPEAK_CUES = "speak_cues"
        const val KEY_ENGINE_READOUT = "show_engine_readout"
        const val KEY_API_BASE_URL = "api_base_url"
    }
}
