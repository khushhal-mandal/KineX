package com.kinex.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.kinex.pose.Violation
import java.util.Locale

/**
 * Speaks the rep count as each rep lands, and the correction for anything the engine flagged.
 *
 * **The queue policy is the whole design, and it is not "say everything".** Reps arrive about
 * every two seconds at a working tempo and faster than that on a light set; the number takes
 * roughly half a second to speak and a cue about a second more. Queue both with `QUEUE_ADD`
 * and the backlog never drains — by the eighth rep the athlete hears "three" while the screen
 * reads 8, which is worse than silence because it is a confident wrong number.
 *
 * So the count is spoken with [TextToSpeech.QUEUE_FLUSH] and the cues behind it with
 * `QUEUE_ADD`. A new rep interrupts whatever is still being said and states the current
 * number. What that loses under fast reps is the cues, and that is the correct thing to lose:
 * the count has to be right or it is a lie about something the athlete is tracking, whereas a
 * missed cue is advice that did not arrive.
 *
 * **Nothing here is on the critical path and nothing here fails loudly.** A device with no TTS
 * voice data, or an engine that will not initialise, leaves the workout counting and drawing
 * exactly as before — the failure is logged and never reaches [com.kinex.ui.WorkoutViewModel]'s
 * error banner, because an optional feature being unavailable is not a reason to put a red
 * message over a working rep counter.
 */
class CueSpeaker(context: Context) {

    private var engine: TextToSpeech? = null

    /**
     * Written on the main thread from the init callback, read from the analysis or replay
     * thread on every counted rep. Volatile rather than synchronized: it goes false→true once
     * and a rep that reads a stale false is simply not spoken.
     */
    @Volatile
    private var ready = false

    init {
        // The listener fires on the main thread, some hundreds of milliseconds later. A rep
        // landing before then is dropped rather than queued — a number spoken after the fact
        // is a wrong number, and the first rep of a set is the one nobody is waiting on.
        val tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS && applyLanguage()
            if (!ready) Log.w(TAG, "text-to-speech unavailable, cues are off (status $status)")
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            // Not decoration: "speak() returned SUCCESS" and "the athlete heard something" are
            // different claims, and a device missing its voice data satisfies the first and not
            // the second. This is the only place the difference is observable.
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) {
                Log.d(TAG, "spoke $utteranceId")
            }

            @Deprecated("Overridden because the base class requires it; the two-arg form is used.")
            override fun onError(utteranceId: String?) = Unit
            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.w(TAG, "failed to speak $utteranceId (error $errorCode)")
            }
        })
        engine = tts
    }

    /**
     * `Locale.US` rather than the device default, because every string this speaks is hardcoded
     * English — [Violation.cue] and a bare integer. Handing English text to a synthesiser set
     * to another language produces sounds rather than words.
     */
    private fun applyLanguage(): Boolean {
        val result = engine?.setLanguage(Locale.US) ?: return false
        return result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
    }

    /**
     * Announce a rep that has just counted, and whatever it was flagged for.
     *
     * Called from whichever thread the frames are on. `TextToSpeech.speak` is a binder call and
     * is safe from any of them.
     */
    fun speakRep(repCount: Int, violations: List<Violation>) {
        val tts = engine
        if (tts == null || !ready) return
        cueUtterances(repCount, violations).forEachIndexed { index, utterance ->
            // Index 0 is the count, and it is the one that flushes. See the class comment.
            val mode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            tts.speak(utterance, mode, null, "rep$repCount-$index")
        }
    }

    fun close() {
        ready = false
        engine?.stop()
        engine?.shutdown()
        engine = null
    }

    private companion object {
        const val TAG = "KineXCues"
    }
}

/**
 * What a counted rep should say, in order: the number first, then one correction per flagged
 * rule.
 *
 * Split out from [CueSpeaker.speakRep] because it is the part worth testing — the order, and
 * that the spoken text is [Violation.cue] rather than [Violation.label] — and testing it
 * through a real `TextToSpeech` would need a device and an installed voice.
 *
 * The number goes first because it is the thing the athlete is counting on. A cue that arrives
 * before the count makes them wait to find out where they are.
 */
fun cueUtterances(repCount: Int, violations: List<Violation>): List<String> =
    listOf(repCount.toString()) + violations.map { it.cue }
