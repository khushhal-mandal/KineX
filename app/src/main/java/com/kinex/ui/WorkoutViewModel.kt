package com.kinex.ui

import android.app.Application
import android.os.SystemClock
import androidx.camera.core.CameraSelector
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.kinex.data.AppSettings
import com.kinex.data.RecordedRep
import com.kinex.data.SessionRepository
import com.kinex.pose.Calibrator
import com.kinex.pose.Exercise
import com.kinex.pose.LandmarkRecorder
import com.kinex.pose.PoseFrame
import com.kinex.pose.PoseLandmarkerHelper
import com.kinex.pose.RepCounter
import com.kinex.pose.RepSnapshot
import com.kinex.pose.RepState
import com.kinex.pose.Violation
import com.kinex.sync.SessionSyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Everything a workout is, for as long as the Workout destination is on the back stack.
 *
 * This was `PoseCameraState`, a plain object held by `remember` inside the camera composable.
 * The move is not tidying: a `remember` dies on a configuration change, so **rotating the
 * phone used to destroy the native engine, drop the calibration and zero the rep count**
 * mid-set. A ViewModel scoped to the navigation entry survives rotation and dies when the
 * destination leaves the back stack, which is exactly the lifetime a set has.
 *
 * What that buys, concretely:
 * - The engine handle outlives rotation. [onCleared] is the one place it is destroyed, so
 *   there is a single owner rather than a composable racing a frame in flight on disposal.
 * - The MediaPipe detector outlives rotation too, so a rotate no longer reloads the 5.5 MB
 *   model. It is rebuilt only when the lens changes, because `mirrorX` is immutable.
 * - The set in flight, the calibration and the counted reps all survive.
 *
 * What deliberately does **not** live here: the `PreviewView` and the CameraX binding. Both
 * hold an Activity and a Lifecycle, and a ViewModel that outlives the Activity holding either
 * is a leak. They are rebuilt by the composable on every configuration change, which is cheap
 * and is what CameraX expects. "The camera session survives" means the workout survives, not
 * that the surface is never re-created.
 *
 * Threading is unchanged from the object this replaces. [onFrame] arrives on MediaPipe's
 * result thread when live and on the replay coroutine otherwise; snapshot state writes are
 * safe from any thread, and the engine swap points are `@Synchronized`.
 */
class WorkoutViewModel(
    application: Application,
    initialExercise: Exercise,
) : AndroidViewModel(application) {

    private val repository = SessionRepository.get(application)
    private val recorder = LandmarkRecorder(
        File(application.getExternalFilesDir(null) ?: application.filesDir, "recordings")
    ) { error = it }
    private val calibrator = Calibrator()

    /**
     * Where a set is written from, and deliberately **not** [androidx.lifecycle.viewModelScope].
     *
     * viewModelScope is cancelled at the top of [onCleared], and the last thing this object
     * does is flush the set in flight. Launching that final write into a scope that is already
     * being torn down would abort exactly the save that "an offline workout survives a
     * restart" depends on. Every job here is one bounded insert, so an uncancelled scope
     * leaks nothing — the jobs finish and the scope becomes garbage.
     */
    private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The set in flight — reps counted since the last [endSet], and its two frame-clock ends. */
    private val setReps = mutableListOf<RecordedRep>()
    private var setStartedAtMs = 0L
    private var firstRepFrameMs = 0L
    private var lastRepFrameMs = 0L

    // Swapped whenever the exercise changes or a calibration completes — both of those are
    // fixed at create() and neither can be changed on a live handle. Read on the analysis
    // thread every frame, written from there and from the main thread, hence volatile.
    @Volatile
    private var counter: RepCounter? = null

    private var landmarker: PoseLandmarkerHelper? = null
    private var landmarkerLens: Int? = null

    /**
     * The single thread every frame is converted and submitted on.
     *
     * It lives here rather than in the composable, and that is a correctness requirement
     * rather than tidiness. Closing a `PoseLandmarker` while a `detectAsync` is in flight on
     * it is a crash, so the close has to be *queued behind* any frame already running — which
     * is only possible if whoever closes the detector can reach the thread the frames run on.
     * When the composable owned the executor it shut it down on disposal and the detector was
     * closed from the main thread, unordered against a conversion still in progress.
     *
     * Surviving configuration changes is the second benefit: a rotation no longer churns a
     * thread, and no frame is dropped to a restarting executor.
     */
    val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    var frame by mutableStateOf<PoseFrame?>(null)
    var fps by mutableFloatStateOf(0f)
    var error by mutableStateOf<String?>(null)
    var recordingFile by mutableStateOf<File?>(null)
    var recordedFrames by mutableIntStateOf(0)

    /**
     * Which lens the workout is on. Held here rather than in the composable so a rotation does
     * not silently flip a front-camera session back to the settings default.
     */
    var lensFacing by mutableIntStateOf(AppSettings(application).defaultLensFacing)
        private set

    /** Which row of the native config table this session is running. */
    var exercise by mutableStateOf(initialExercise)
        private set

    /**
     * The id of a set that just ended because the athlete stopped, for the summary screen.
     *
     * Only the idle timeout sets this. Every other ending — backgrounding, changing exercise,
     * recalibrating, switching camera, leaving the screen — also writes a set, and none of
     * them is a moment to push a summary in front of somebody: they are either already
     * leaving or already doing something else.
     */
    var savedSessionId by mutableStateOf<Long?>(null)

    /**
     * The angle this set is judged against — progress 0 — or null while it is still being
     * captured. Doubles as the flag for which readout is on screen, because there is no set
     * until there is a start angle.
     */
    var startAngleDegrees by mutableStateOf<Float?>(null)
        private set

    /** How long the calibration pose has been held, for the countdown. */
    var calibrationHeldMs by mutableLongStateOf(0L)
        private set

    /**
     * Whether the athlete has said they are in the start pose.
     *
     * The countdown does not run until they have. Before this the screen shows the pose the
     * exercise expects and waits, which is the whole fix for the shoulder press: the sweep
     * calibrated it in the *finish* pose — arms straight overhead — and captured 174 degrees
     * against a 170 target, because the countdown had already started and nothing on screen
     * said what it was waiting for. Describing the pose while the count is already running is
     * not the same as describing it first.
     */
    var poseConfirmed by mutableStateOf(false)
        private set

    /**
     * Set when the engine refused the captured calibration — the start pose was closer to the
     * exercise's target than half its configured span, so almost any movement would read as a
     * complete rep. Cleared when the athlete starts another attempt.
     *
     * The refusal is the engine's: `create()` returns no handle. That is the same null Kotlin
     * gets from a missing native library, and it is unambiguous here because the uncalibrated
     * engine for this same exercise was opened successfully moments earlier — the library is
     * demonstrably present, so the span guard is the only thing that can have refused.
     */
    var calibrationRefused by mutableStateOf(false)
        private set

    /** Slot [5]: 1.0 when the alignment gate passes, lower the further the athlete is from it. */
    var alignmentScore by mutableFloatStateOf(0f)
        private set

    var repCount by mutableIntStateOf(0)
        private set
    var repState by mutableStateOf(RepState.IDLE)
        private set
    var primaryAngleDegrees by mutableFloatStateOf(0f)
        private set

    /** Slot [4], clamped 0..1 by the engine. Drives the HUD progress ring and nothing else. */
    var repProgress by mutableFloatStateOf(0f)
        private set

    /**
     * Highest progress the last counted rep reached, and what it was flagged for.
     *
     * Both come straight off the engine — slot [6] and slot [2] — and both describe the same
     * rep, held until the next one counts.
     */
    var lastRepPeakProgress by mutableFloatStateOf(0f)
        private set
    var lastRepViolations by mutableStateOf<List<Violation>>(emptyList())
        private set

    private var lastCountedRep = 0

    private var framesInWindow = 0
    private var windowStartMs = 0L

    init {
        recalibrate()
    }

    /**
     * The detector for [lens], built once and kept across configuration changes.
     *
     * Rebuilt only when the lens changes, because `mirrorX` is an immutable constructor
     * argument — which is itself deliberate, so no flag is shared between the analysis thread
     * and the main one.
     *
     * The outgoing detector is closed **on [analysisExecutor]**, not here. By the time a
     * different lens asks for a new one the composable has already unbound the camera, so no
     * further frame will be submitted — but one may still be mid-conversion on that thread,
     * and closing a detector under a running `detectAsync` is a crash. Queueing the close puts
     * it behind that frame.
     */
    @Synchronized
    fun landmarkerFor(lens: Int): PoseLandmarkerHelper {
        val existing = landmarker
        if (existing != null && landmarkerLens == lens) return existing
        if (existing != null) analysisExecutor.execute { existing.close() }
        val built = PoseLandmarkerHelper(
            context = getApplication(),
            onFrame = ::onFrame,
            onError = { error = it },
            // PreviewView already mirrors a front preview; this is what makes the landmarks
            // agree with what is on screen.
            mirrorX = lens == CameraSelector.LENS_FACING_FRONT,
        )
        landmarker = built
        landmarkerLens = lens
        return built
    }

    fun onFrame(pose: PoseFrame?) {
        frame = pose

        // Frames without a pose carry no landmarks to write, so they leave no line.
        if (pose != null && recordingFile != null) {
            recorder.record(pose)
            recordedFrames++
        }

        // A frame with no pose has nothing to hand the engine. Its clock therefore does not
        // advance on dropped detections, which is what the fixture replays do too.
        if (pose != null) {
            counter?.process(pose.points, pose.timestampMs)?.let {
                // Before a start angle exists, the engine is running against the documented
                // uncalibrated fallback and only its slot [3] means anything. After one, the
                // engine was rebuilt around it and every slot is about this athlete.
                if (startAngleDegrees == null) {
                    observeCalibration(it.primaryAngleDegrees, pose.timestampMs)
                } else {
                    observeSet(it, pose.timestampMs)
                }
            }
        }

        // Counts results, not submitted frames, so this is the end-to-end rate the
        // user actually sees — dropped frames included.
        val now = SystemClock.uptimeMillis()
        if (windowStartMs == 0L) {
            windowStartMs = now
            return
        }
        framesInWindow++
        val elapsed = now - windowStartMs
        if (elapsed >= FPS_WINDOW_MS) {
            fps = framesInWindow * 1000f / elapsed
            framesInWindow = 0
            windowStartMs = now
        }
    }

    /**
     * One frame of the calibration hold. Synchronized against [select] and [recalibrate], so
     * the engine this installs on the last frame of a hold cannot be one for an exercise the
     * picker moved off in between.
     */
    @Synchronized
    private fun observeCalibration(angleDegrees: Float, timestampMs: Long) {
        primaryAngleDegrees = angleDegrees
        // The live angle keeps updating while the pose card is up, so the athlete can see the
        // model is tracking them before they commit to holding still for three seconds. The
        // hold itself does not start until they say they are in position.
        if (!poseConfirmed) return

        val captured = calibrator.observe(angleDegrees, timestampMs)
        calibrationHeldMs = calibrator.heldMs
        if (captured == null) return

        val counter = RepCounter.open(exercise.id, captured)
        if (counter == null) {
            // Refused. Back to the pose card with an explanation rather than on into a set
            // that would count almost any movement as a rep. The uncalibrated engine is still
            // installed and still reporting slot [3], so nothing is swapped or closed here.
            calibrationRefused = true
            poseConfirmed = false
            calibrator.reset()
            calibrationHeldMs = 0L
            return
        }
        startAngleDegrees = captured
        swap(counter)
    }

    /**
     * The athlete says they are in the start pose. Starts the hold.
     */
    @Synchronized
    fun confirmPose() {
        calibrationRefused = false
        calibrator.reset()
        calibrationHeldMs = 0L
        poseConfirmed = true
    }

    private fun observeSet(snapshot: RepSnapshot, timestampMs: Long) {
        repCount = snapshot.repCount
        repState = snapshot.state
        primaryAngleDegrees = snapshot.primaryAngleDegrees
        repProgress = snapshot.repProgress
        alignmentScore = snapshot.alignmentScore

        // Strictly greater, so the engine restarting at 0 cannot latch a rep. An attempt the
        // FSM discarded never gets here — it produces no count — and slot [6] holds the last
        // rep that did count until another one does.
        if (snapshot.repCount > lastCountedRep) {
            lastCountedRep = snapshot.repCount
            lastRepPeakProgress = snapshot.repPeakProgress
            lastRepViolations = Violation.decode(snapshot.violationMask)
            recordRep(snapshot, timestampMs)
        }

        // A set that has gone quiet is a set that ended. Measured on the frame clock, from
        // the last rep rather than the last frame: standing in front of the camera between
        // sets is not activity, and the alternative would keep a set open forever as long as
        // somebody stayed in shot.
        if (setReps.isNotEmpty() && timestampMs - lastRepFrameMs > SET_IDLE_TIMEOUT_MS) {
            // The one ending that means "the athlete has stopped", so the one that announces
            // itself to the summary screen.
            endSet(announce = true)
        }
    }

    /**
     * Appends one counted rep to the set in flight, and starts the set if this is its first.
     *
     * The wall clock is read once, here, for the history row's date. Everything else runs on
     * the frame clock, which cannot step sideways mid-set.
     */
    private fun recordRep(snapshot: RepSnapshot, timestampMs: Long) {
        if (setReps.isEmpty()) {
            setStartedAtMs = System.currentTimeMillis()
            firstRepFrameMs = timestampMs
        }
        lastRepFrameMs = timestampMs
        setReps += RecordedRep(
            peakProgress = snapshot.repPeakProgress,
            violationMask = snapshot.violationMask,
        )
    }

    /**
     * Ends the set in flight: writes it if it had any reps, then puts the engine and the HUD
     * back to zero so the next set starts clean.
     *
     * Called on the idle timeout, on every teardown that swaps or drops the engine, and when
     * the app is backgrounded. The last of those is what "survives closing the app" rests on
     * — a ViewModel is not cleared when the user presses Home, so waiting for [onCleared]
     * would lose the set to the first process kill.
     *
     * A set with no reps writes nothing. It still clears, because a set that never started is
     * not a set to keep open.
     *
     * [announce] publishes the written set's id on [savedSessionId] so the screen above can
     * navigate to its summary. Only the idle timeout passes true — see that field.
     */
    @Synchronized
    fun endSet(announce: Boolean = false) {
        val reps = setReps.toList()
        setReps.clear()
        if (reps.isNotEmpty()) {
            val exerciseId = exercise.id
            val startedAtMs = setStartedAtMs
            val durationMs = lastRepFrameMs - firstRepFrameMs
            saveScope.launch {
                try {
                    val sessionId = repository.saveSet(exerciseId, startedAtMs, durationMs, reps)
                    // Published only after the row exists, so the summary screen's query
                    // cannot race the insert that created what it is querying for.
                    if (announce && sessionId != null) savedSessionId = sessionId
                    // Asked for after the row exists, for the same reason: the worker reads
                    // unsynced rows out of the database, so enqueueing before the insert is a
                    // request to upload a set that is not there yet. WorkManager holds it
                    // until the Wi-Fi constraint is met, so this costs nothing offline.
                    if (sessionId != null) SessionSyncWorker.enqueue(getApplication())
                } catch (failure: Exception) {
                    error = "Could not save the set: ${failure.message}"
                }
            }
        }
        counter?.reset()
        clearSet()
    }

    /**
     * Points the session at a different row of the config table. The exercise is fixed at
     * `create()`, so this is an engine swap rather than a setter, and it recalibrates: a
     * squat's start pose is not a curl's, and the captured angle belongs to the pose.
     */
    @Synchronized
    fun select(exercise: Exercise) {
        if (exercise == this.exercise) return
        this.exercise = exercise
        recalibrate()
    }

    /**
     * Drops the captured start angle and opens an uncalibrated engine to read the next one
     * out of. The count goes with it — a set counted from one start angle and a set counted
     * from another are not the same set — which is also the only way to zero the count
     * without leaving the screen.
     */
    @Synchronized
    fun recalibrate() {
        // Before the engine goes: whatever was counted against the old start angle is a
        // finished set, and it is finished whether or not the athlete thought of it that way.
        endSet()
        calibrator.reset()
        calibrationHeldMs = 0L
        startAngleDegrees = null
        // Every recalibration goes back through the pose card. Changing exercise is the case
        // that matters: the athlete is standing in the last exercise's pose, and the new row
        // may want something entirely different.
        poseConfirmed = false
        calibrationRefused = false
        swap(RepCounter.openUncalibrated(exercise.id))
    }

    /**
     * Installs [next] and closes what it replaced, in that order: a frame already holding the
     * old engine then gets a null out of [RepCounter.process] rather than a count from an
     * engine nothing is looking at any more.
     */
    @Synchronized
    private fun swap(next: RepCounter?) {
        val previous = counter
        counter = next
        previous?.close()
        if (next == null) {
            error = "Native engine unavailable — rep counting is off"
        }
    }

    /**
     * Mirroring moves every x by up to a full frame width between one frame and the next.
     * One Euro keys its cutoff off a one-frame derivative, so carrying filter state across
     * that step would read the switch as the fastest motion of the session; the in-flight
     * rep belongs to the old view besides. Reset drops both and keeps the calibration.
     *
     * The calibration survives on purpose. Every quantity the engine computes is
     * reflection-invariant, so the angle captured on one lens is the same angle on the other
     * — see "Camera facing and mirroring" in the design doc — and making the athlete hold a pose
     * again for a number that cannot have changed would be ceremony.
     *
     * Clearing [frame] stops the overlay drawing the old camera's pose, in the old
     * handedness, over the new preview during the rebind gap.
     */
    fun switchCamera() {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        // The count restarts across a switch, so the set does too — and a set that restarts
        // is one that ended. endSet resets the engine and clears the HUD.
        endSet()
        frame = null
    }

    private fun clearSet() {
        repCount = 0
        repState = RepState.IDLE
        primaryAngleDegrees = 0f
        repProgress = 0f
        lastRepPeakProgress = 0f
        lastRepViolations = emptyList()
        // Back to 0 alongside the engine's own count, or the first rep of the new set would
        // have to beat the old set's total before it latched anything.
        lastCountedRep = 0
    }

    fun toggleRecording() {
        if (recordingFile == null) {
            recordedFrames = 0
            error = null
            recordingFile = recorder.start()
        } else {
            recorder.stop()
            recordingFile = null
        }
    }

    /**
     * The end of the workout, and the only place the native handle is destroyed.
     *
     * Reached when the Workout destination leaves the back stack — including the pop that the
     * set summary performs — and never on a configuration change, which is the whole point of
     * the engine living here.
     *
     * Order matters. The set is flushed first, while the engine and the store are both still
     * usable. The detector's close is then queued on [analysisExecutor] so it lands behind any
     * frame still being converted, and the executor is shut down after it — `shutdown` lets
     * already-submitted work finish, so the queued close is not discarded. The engine goes
     * last: [RepCounter.close] and [RepCounter.process] are both `@Synchronized` against the
     * same monitor, so a frame that slipped through either completes before the close or sees
     * a zero handle and returns null. It never reads a freed one.
     */
    override fun onCleared() {
        // Last chance for a set nobody ended explicitly. Backgrounding already flushed one via
        // the lifecycle observer, so this normally has nothing to write.
        endSet()
        val detector = landmarker
        landmarker = null
        landmarkerLens = null
        if (detector != null) analysisExecutor.execute { detector.close() }
        analysisExecutor.shutdown()
        recorder.close()
        counter?.close()
        counter = null
    }

    companion object {
        private const val FPS_WINDOW_MS = 500L

        /**
         * How long a set may go without a rep before it counts as over.
         *
         * Untuned, and chosen long on purpose: rest between working sets is routinely 60-120
         * seconds, and a short timeout splits one real set into two history rows, which is a
         * wrong record rather than an inconvenience. Too long costs nothing — every teardown
         * path ends the set anyway, so the worst case is a set that stays open until the
         * athlete changes exercise, recalibrates, or leaves the screen.
         */
        private const val SET_IDLE_TIMEOUT_MS = 120_000L

        /**
         * The exercise cannot be a plain constructor default: it comes off the navigation
         * route, so it has to reach the ViewModel through the factory that builds it.
         */
        fun factory(initialExercise: Exercise): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                WorkoutViewModel(
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application,
                    initialExercise,
                )
            }
        }
    }
}
